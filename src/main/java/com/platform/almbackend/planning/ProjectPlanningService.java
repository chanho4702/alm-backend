package com.platform.almbackend.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.ProjectPlanning;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.ProjectPlanningRepository;
import com.platform.common.error.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 프로젝트 계획 설정(V39) — 사이클(스프린트 자동화)·추정 척도. 읽기 VIEW, 쓰기 ADMIN(트리아지 설정과 같은 결).
 * 다른 서비스(스프린트 완료·이슈 저장)는 {@link #settingsOf(long)}로 해석된 값을 읽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectPlanningService {
    static final BigDecimal MAX_POINTS = new BigDecimal("9999");
    static final int MAX_CUSTOM = 20;

    private final ProjectPlanningRepository repository;
    private final ProjectService projectService;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public PlanningDtos.Response get(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return response(projectId, settingsOf(projectId));
    }

    public PlanningDtos.Response update(long userId, long projectId, PlanningDtos.UpdateRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        ProjectPlanning row = repository.findById(projectId).orElse(null);
        PlanningSettings current = row == null ? PlanningSettings.DEFAULTS : read(row);
        if (current.version() != request.expectedVersion()) {
            throw new ConflictException("다른 사용자가 먼저 계획 설정을 수정했습니다. 현재 "
                    + current.version() + ", 요청 " + request.expectedVersion());
        }
        PlanningDtos.SprintAutomation auto = request.sprintAutomation();
        boolean autoCreate = auto == null || auto.autoCreateNext() == null ? current.autoCreateNext() : auto.autoCreateNext();
        int length = auto == null || auto.lengthDays() == null ? current.lengthDays() : auto.lengthDays();
        int cooldown = auto == null || auto.cooldownDays() == null ? current.cooldownDays() : auto.cooldownDays();
        String carry = auto == null || auto.defaultCarryOver() == null ? current.defaultCarryOver()
                : auto.defaultCarryOver().trim().toLowerCase(java.util.Locale.ROOT);
        if (length < 1 || length > 90) throw new IllegalArgumentException("스프린트 길이는 1~90일이어야 합니다");
        if (cooldown < 0 || cooldown > 30) throw new IllegalArgumentException("쿨다운은 0~30일이어야 합니다");
        if (!PlanningSettings.CARRY_OVER.contains(carry)) {
            throw new IllegalArgumentException("미완료 이슈 기본 이관 대상은 next·backlog 중 하나여야 합니다: " + carry);
        }
        String scale = request.estimationScale() == null ? current.scale()
                : request.estimationScale().trim().toLowerCase(java.util.Locale.ROOT);
        if (!PlanningSettings.SCALES.contains(scale)) {
            throw new IllegalArgumentException("알 수 없는 추정 척도입니다: " + request.estimationScale()
                    + " (fibonacci·tshirt·linear·custom)");
        }
        List<BigDecimal> custom = request.customValues() == null ? current.customValues() : normalizeCustom(request.customValues());
        Map<String, BigDecimal> tshirt = request.tshirtPoints() == null ? current.tshirtPoints() : normalizeTshirt(request.tshirtPoints());
        if ("custom".equals(scale) && custom.isEmpty()) {
            throw new IllegalArgumentException("사용자 지정 척도에는 값이 하나 이상 있어야 합니다");
        }
        if (row == null) row = ProjectPlanning.of(projectId);
        row.apply(autoCreate, length, cooldown, carry, scale, custom.isEmpty() ? null : write(custom), write(tshirt),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // 행이 없던 두 요청이 동시에 처음 저장했다
            throw new ConflictException("다른 사용자가 먼저 계획 설정을 수정했습니다. 새로 고친 뒤 다시 저장하세요");
        }
        return response(projectId, read(row));
    }

    /** 해석된 설정 — 행이 없으면 기본값. 권한을 묻지 않는다(호출자가 이미 판정했다) */
    @Transactional(readOnly = true)
    public PlanningSettings settingsOf(long projectId) {
        return repository.findById(projectId).map(this::read).orElse(PlanningSettings.DEFAULTS);
    }

    /**
     * 스토리 포인트가 이 프로젝트 척도에 있는가 — 값이 <b>바뀔 때만</b> 부른다(척도를 바꿔도 손대지 않은 옛 값은 그대로 둔다).
     */
    public void assertPoints(long projectId, BigDecimal points) {
        if (points == null) return;
        PlanningSettings settings = settingsOf(projectId);
        if (settings.allows(points)) return;
        String allowed = settings.allowedPoints().stream().map(v -> v.stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(", "));
        throw new IllegalArgumentException("스토리 포인트 " + points.stripTrailingZeros().toPlainString()
                + "은(는) 이 프로젝트의 추정 척도(" + settings.scaleLabel() + ")에 없습니다 — 허용: " + allowed);
    }

    static List<BigDecimal> normalizeCustom(List<BigDecimal> values) {
        if (values.size() > MAX_CUSTOM) throw new IllegalArgumentException("사용자 지정 척도 값은 " + MAX_CUSTOM + "개까지입니다");
        List<BigDecimal> out = new ArrayList<>();
        for (BigDecimal value : values) {
            BigDecimal v = point(value, "사용자 지정 척도 값");
            if (out.stream().anyMatch(x -> x.compareTo(v) == 0)) {
                throw new IllegalArgumentException("사용자 지정 척도 값이 겹칩니다: " + v.toPlainString());
            }
            out.add(v);
        }
        out.sort(BigDecimal::compareTo);
        return List.copyOf(out);
    }

    static Map<String, BigDecimal> normalizeTshirt(Map<String, BigDecimal> values) {
        if (values.size() != PlanningSettings.TSHIRT_SIZES.size() || !values.keySet().containsAll(PlanningSettings.TSHIRT_SIZES)) {
            throw new IllegalArgumentException("T셔츠 매핑에는 XS·S·M·L·XL 다섯 개가 모두 있어야 합니다");
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal previous = null;
        for (String size : PlanningSettings.TSHIRT_SIZES) {
            BigDecimal v = point(values.get(size), "T셔츠 " + size + " 포인트");
            if (previous != null && v.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("T셔츠 포인트는 크기 순으로 커져야 합니다(" + size + ")");
            }
            out.put(size, v);
            previous = v;
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static BigDecimal point(BigDecimal value, String what) {
        if (value == null) throw new IllegalArgumentException(what + "이(가) 비어 있습니다");
        if (value.signum() < 0 || value.compareTo(MAX_POINTS) > 0) {
            throw new IllegalArgumentException(what + "은(는) 0~9999 사이여야 합니다");
        }
        if (value.stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException(what + "은(는) 소수 첫째 자리까지만 쓸 수 있습니다");
        }
        return plain(value.setScale(1, RoundingMode.UNNECESSARY));
    }

    /** 3.0 → 3, 10 → 10(1E+1이 아니라) — JSON에 지수 표기가 나가지 않게 */
    static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private PlanningSettings read(ProjectPlanning row) {
        List<BigDecimal> custom = row.getCustomValues() == null ? List.of()
                : parse(row.getCustomValues(), new TypeReference<List<BigDecimal>>() {}, List.of());
        Map<String, BigDecimal> tshirt = row.getTshirtPoints() == null ? PlanningSettings.TSHIRT_DEFAULT
                : parse(row.getTshirtPoints(), new TypeReference<LinkedHashMap<String, BigDecimal>>() {}, PlanningSettings.TSHIRT_DEFAULT);
        return new PlanningSettings(row.isAutoCreateNext(), row.getSprintLengthDays(), row.getCooldownDays(),
                row.getDefaultCarryOver(), row.getEstimationScale(), List.copyOf(custom), tshirt, row.getVersion());
    }

    private <T> T parse(String text, TypeReference<? extends T> type, T fallback) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            log.warn("계획 설정 JSON을 읽지 못해 기본값을 씁니다: {}", e.getOriginalMessage());
            return fallback;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlanningDtos.Response response(long projectId, PlanningSettings s) {
        List<BigDecimal> allowed = s.allowedPoints();
        return new PlanningDtos.Response(projectId,
                new PlanningDtos.SprintAutomation(s.autoCreateNext(), s.lengthDays(), s.cooldownDays(), s.defaultCarryOver()),
                s.scale(), strip(s.customValues()), stripMap(s.tshirtPoints()), allowed == null ? null : strip(allowed), s.version());
    }

    private static List<BigDecimal> strip(List<BigDecimal> values) {
        return values.stream().map(ProjectPlanningService::plain).toList();
    }

    private static Map<String, BigDecimal> stripMap(Map<String, BigDecimal> values) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, plain(v)));
        return out;
    }
}
