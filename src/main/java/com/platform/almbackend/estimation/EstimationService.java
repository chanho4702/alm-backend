package com.platform.almbackend.estimation;

import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.settings.SchemeService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 스토리 포인트·예상 시간 합계(V30) — 백로그·스프린트 머리글의 합계와 속도(velocity) 리포트.
 *
 * <p>추정 단위는 고를 수 있다({@link Unit}): 포인트·시간·이슈 수. 완료 판정은 서버 워크플로의 완료 의미(complete)다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstimationService {

    private static final int DEFAULT_SPRINTS = 7;
    private static final int MAX_SPRINTS = 20;

    private final EstimationQueries queries;
    private final SprintRepository sprints;
    private final ProjectService projects;
    private final SchemeService settings;

    /** 추정 단위 — 리포트가 무엇으로 셀지 */
    public enum Unit {
        POINTS, HOURS, COUNT;

        public static Unit parse(String value) {
            if (value == null || value.isBlank()) return POINTS;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("추정 단위는 points·hours·count 중 하나입니다");
            }
        }
    }

    @Schema(description = "스프린트(또는 백로그) 하나의 추정 합계")
    public record Group(
            @Schema(description = "스프린트 ID. 백로그면 null", example = "12") Long sprintId,
            @Schema(description = "스프린트 이름. 백로그면 \"백로그\"", example = "Sprint 3") String name,
            @Schema(description = "PLANNED·ACTIVE, 백로그면 null", example = "ACTIVE") String state,
            @Schema(description = "이슈 수", example = "8") long issueCount,
            @Schema(description = "완료 이슈 수", example = "3") long completedCount,
            @Schema(description = "스토리 포인트 합(미추정은 0으로 센다)", example = "21") BigDecimal storyPoints,
            @Schema(description = "완료된 이슈의 포인트 합", example = "8") BigDecimal completedStoryPoints,
            @Schema(description = "포인트가 비어 있는 이슈 수", example = "2") long unestimatedCount,
            @Schema(description = "예상 시간 합", example = "30.5") BigDecimal estimateHours,
            @Schema(description = "완료된 이슈의 예상 시간 합", example = "10") BigDecimal completedEstimateHours) {}

    @Schema(description = "프로젝트 추정 합계 — 진행 중·예정 스프린트(번호 순) 뒤에 백로그")
    public record Summary(long projectId, List<Group> groups) {}

    @Schema(description = "완료된 스프린트 하나의 속도")
    public record VelocityRow(
            @Schema(example = "11") long sprintId,
            @Schema(example = "Sprint 2") String name,
            Instant completedAt,
            @Schema(description = "완료 시점 범위 = 완료 + 그 순간 이월(같은 단위)", example = "24") BigDecimal committed,
            @Schema(description = "완료한 양", example = "18") BigDecimal completed) {}

    @Schema(description = "속도 리포트 — 최근 완료 스프린트부터. average는 completed 평균(소수 첫째 자리)")
    public record Velocity(long projectId, @Schema(example = "points") String unit, List<VelocityRow> sprints,
                           @Schema(example = "17.5") BigDecimal average) {}

    public Summary summary(long userId, long projectId) {
        projects.require(userId, projectId, AlmAction.VIEW);
        Set<String> done = doneStatuses(projectId);
        Map<Long, Object[]> bySprint = new HashMap<>();
        Object[] backlog = null;
        for (Object[] row : queries.totalsBySprint(projectId, done)) {
            if (row[0] == null) backlog = row;
            else bySprint.put(((Number) row[0]).longValue(), row);
        }
        List<Group> groups = new ArrayList<>();
        for (Sprint sprint : sprints.findByProjectIdOrderBySprintNumberAsc(projectId)) {
            if (sprint.getState() == SprintState.DONE) continue;
            groups.add(group(sprint.getId(), sprint.getName(), sprint.getState().name(), bySprint.get(sprint.getId())));
        }
        groups.add(group(null, "백로그", null, backlog));
        return new Summary(projectId, groups);
    }

    public Velocity velocity(long userId, long projectId, String unitText, Integer limit) {
        projects.require(userId, projectId, AlmAction.VIEW);
        Unit unit = Unit.parse(unitText);
        String unitName = unit.name().toLowerCase(Locale.ROOT);
        int size = Math.max(1, Math.min(limit == null ? DEFAULT_SPRINTS : limit, MAX_SPRINTS));
        List<Sprint> completed = sprints.findByProjectIdOrderBySprintNumberAsc(projectId).stream()
                .filter(s -> s.getState() == SprintState.DONE && s.getCompletedAt() != null)
                .sorted(Comparator.comparing(Sprint::getCompletedAt).reversed())
                .limit(size)
                .toList();
        if (completed.isEmpty()) return new Velocity(projectId, unitName, List.of(), BigDecimal.ZERO);
        Set<String> done = doneStatuses(projectId);
        Map<Long, Object[]> completedRows = new HashMap<>();
        for (Object[] row : queries.completedIn(projectId, completed.stream().map(Sprint::getId).toList(), done)) {
            completedRows.put(((Number) row[0]).longValue(), row);
        }
        List<VelocityRow> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Sprint sprint : completed) {
            Object[] row = completedRows.get(sprint.getId());
            BigDecimal completedAmount = row == null ? BigDecimal.ZERO : switch (unit) {
                case POINTS -> decimal(row[2]);
                case HOURS -> decimal(row[3]);
                case COUNT -> BigDecimal.valueOf(count(row[1]));
            };
            BigDecimal carried = BigDecimal.ZERO;
            for (Object[] moved : queries.carriedOver(projectId, String.valueOf(sprint.getId()), sprint.getCompletedAt())) {
                carried = carried.add(switch (unit) {
                    case POINTS -> decimal(moved[0]);
                    case HOURS -> decimal(moved[1]);
                    case COUNT -> BigDecimal.ONE;
                });
            }
            rows.add(new VelocityRow(sprint.getId(), sprint.getName(), sprint.getCompletedAt(),
                    plain(completedAmount.add(carried)), plain(completedAmount)));
            total = total.add(completedAmount);
        }
        BigDecimal average = plain(total.divide(BigDecimal.valueOf(rows.size()), 1, RoundingMode.HALF_UP));
        return new Velocity(projectId, unitName, rows, average);
    }

    /** 완료 의미 상태 — 비어 있으면 IN ()가 되지 않게 없는 id 하나를 둔다 */
    private Set<String> doneStatuses(long projectId) {
        Set<String> done = settings.completeStatusIds(projectId);
        return done.isEmpty() ? Set.of("~none~") : done;
    }

    private static Group group(Long sprintId, String name, String state, Object[] row) {
        if (row == null) {
            return new Group(sprintId, name, state, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new Group(sprintId, name, state, count(row[1]), count(row[2]), decimal(row[3]), decimal(row[4]),
                count(row[5]), decimal(row[6]), decimal(row[7]));
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return plain(value instanceof BigDecimal d ? d : new BigDecimal(value.toString()));
    }

    /** 21.0 → 21, 2.50 → 2.5 — JSON에 같은 값이 여러 모양으로 나가지 않게 */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
