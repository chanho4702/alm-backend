package com.platform.almbackend.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.ProjectSettings;
import com.platform.almbackend.domain.SettingsScheme;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SettingsSchemeRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 스킴/커스텀 본문의 JSON 직렬화와 레지스트리 해석(enrich) — RegistryService와 SchemeService가 함께 쓴다.
 * (둘 사이의 순환을 끊기 위해 여기로 뺐다.)
 */
@Component
@RequiredArgsConstructor
public class SchemeQueries {
    private final ObjectMapper json;
    private final SettingsSchemeRepository schemes;
    private final ProjectSettingsRepository projectSettings;
    private final StatusDefRepository statuses;
    private final StatusCategoryRepository categories;

    public SettingsBody parse(String body) {
        try {
            return json.readValue(body, SettingsBody.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("설정 본문을 읽을 수 없습니다");
        }
    }

    public String serialize(SettingsBody body) {
        try {
            return json.writeValueAsString(body.stored());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("설정 본문을 저장할 수 없습니다");
        }
    }

    /** 레지스트리가 이름·카테고리의 진실 — 캐시는 레지스트리에 없는 옛 id만 버틴다 */
    public SettingsBody enrich(SettingsBody body) {
        Map<String, StatusDef> defs = new LinkedHashMap<>();
        for (StatusDef def : statuses.findAll()) defs.put(def.getId(), def);
        Map<String, StatusCategory> cats = new LinkedHashMap<>();
        for (StatusCategory c : categories.findAll()) cats.put(c.getId(), c);
        List<SettingsBody.WorkflowStatus> enriched = new ArrayList<>();
        for (SettingsBody.WorkflowStatus s : body.statuses()) {
            StatusDef def = defs.get(s.id());
            String categoryId = def != null ? def.getCategoryId() : s.category();
            StatusCategory category = cats.getOrDefault(categoryId, cats.get("todo"));
            String kind = category == null ? "new" : category.getKind();
            enriched.add(new SettingsBody.WorkflowStatus(
                    s.id(), def != null ? def.getName() : s.name(), categoryId, s.order(),
                    kind,
                    category == null ? "neutral" : category.getColor(),
                    // 화면이 늘 아이콘 하나는 그릴 수 있게 여기서 폴백까지 끝낸다(레지스트리 응답은 원본을 준다)
                    StatusIcons.resolve(def == null ? null : def.getIcon(), kind)));
        }
        return new SettingsBody(enriched, body.transitions(), body.layout(), body.enabledTypes(),
                body.enabledPriorities(), body.defaultPriority(), body.fields());
    }

    public String kindOf(String categoryId) {
        return categories.findById(categoryId).map(StatusCategory::getKind).orElse("new");
    }

    /**
     * 상태 표시에 필요한 값 한 묶음. {@code kind}는 카테고리를 못 찾으면 null이고,
     * 호출자는 의미에 딸린 표시(이모지 등)만 생략한다 — 이름은 그대로 쓴다.
     */
    public record StatusLabel(String name, String kind) {}

    /**
     * 상태 id → 이름과 카테고리 의미를 **한 번에**. 이름만/의미만 따로 부르면 같은 행을 두 번 읽는다.
     * 레지스트리에 없으면 empty — 호출자가 그 표시를 통째로 생략한다.
     */
    public Optional<StatusLabel> statusLabel(String statusId) {
        if (statusId == null || statusId.isBlank()) return Optional.empty();
        return statuses.findById(statusId).map(def -> new StatusLabel(
                def.getName(),
                categories.findById(def.getCategoryId()).map(StatusCategory::getKind).orElse(null)));
    }

    /** 스킴·커스텀 본문 전부 */
    public List<SettingsBody> allBodies() {
        List<SettingsBody> bodies = new ArrayList<>();
        for (SettingsScheme scheme : schemes.findAll()) bodies.add(parse(scheme.getBody()));
        for (ProjectSettings ps : projectSettings.findAll()) {
            if (ps.isCustom()) bodies.add(parse(ps.getCustomBody()));
        }
        return bodies;
    }

    public Map<String, Long> statusUsage() {
        Map<String, Long> usage = new LinkedHashMap<>();
        for (StatusDef def : statuses.findAllByOrderByIdAsc()) usage.put(def.getId(), 0L);
        for (SettingsBody body : allBodies()) {
            for (SettingsBody.WorkflowStatus s : body.statuses()) usage.merge(s.id(), 1L, Long::sum);
        }
        return usage;
    }

    /** 본문이 의미(new/active/complete)마다 상태를 갖는가 */
    public boolean coversAllKinds(SettingsBody body) {
        Set<String> kinds = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : enrich(body).statuses()) kinds.add(s.kind());
        return kinds.containsAll(RegistryService.KINDS);
    }

    /** 주어진 상태를 쓰는 본문 중 하나라도 의미를 잃는가(카테고리/의미 변경 가드) */
    public boolean anyBodyLosesKind(Set<String> affectedStatusIds) {
        for (SettingsBody body : allBodies()) {
            boolean uses = body.statuses().stream().anyMatch(s -> affectedStatusIds.contains(s.id()));
            if (uses && !coversAllKinds(body)) return true;
        }
        return false;
    }

    /** 상태 이름·카테고리가 바뀌면 저장된 캐시도 맞춘다 */
    public void syncStatusCache(StatusDef def) {
        for (SettingsScheme scheme : schemes.findAll()) {
            SettingsBody body = parse(scheme.getBody());
            if (body.statuses().stream().anyMatch(s -> s.id().equals(def.getId()))) {
                scheme.replaceBody(serialize(rewrite(body, def)));
            }
        }
        for (ProjectSettings ps : projectSettings.findAll()) {
            if (!ps.isCustom()) continue;
            SettingsBody body = parse(ps.getCustomBody());
            if (body.statuses().stream().anyMatch(s -> s.id().equals(def.getId()))) {
                ps.customize(serialize(rewrite(body, def)));
            }
        }
    }

    private static SettingsBody rewrite(SettingsBody body, StatusDef def) {
        List<SettingsBody.WorkflowStatus> next = new ArrayList<>();
        for (SettingsBody.WorkflowStatus s : body.statuses()) {
            next.add(s.id().equals(def.getId())
                    ? new SettingsBody.WorkflowStatus(s.id(), def.getName(), def.getCategoryId(), s.order(), null, null, null)
                    : s);
        }
        return new SettingsBody(next, body.transitions(), body.layout(), body.enabledTypes(),
                body.enabledPriorities(), body.defaultPriority(), body.fields());
    }

    /** 타입을 지우면 모든 본문의 활성 목록에서도 뺀다 */
    public void removeTypeEverywhere(String typeId) {
        for (SettingsScheme scheme : schemes.findAll()) {
            SettingsBody body = parse(scheme.getBody());
            if (body.enabledTypes().contains(typeId)) scheme.replaceBody(serialize(withoutType(body, typeId)));
        }
        for (ProjectSettings ps : projectSettings.findAll()) {
            if (!ps.isCustom()) continue;
            SettingsBody body = parse(ps.getCustomBody());
            if (body.enabledTypes().contains(typeId)) ps.customize(serialize(withoutType(body, typeId)));
        }
    }

    private static SettingsBody withoutType(SettingsBody body, String typeId) {
        return new SettingsBody(body.statuses(), body.transitions(), body.layout(),
                body.enabledTypes().stream().filter(t -> !t.equals(typeId)).toList(),
                body.enabledPriorities(), body.defaultPriority(), body.fields());
    }

    /** 우선순위를 지우면 모든 본문의 활성 목록에서 빼고, 기본이었다면 남은 첫 항목으로 */
    public void removePriorityEverywhere(String priorityId) {
        for (SettingsScheme scheme : schemes.findAll()) {
            SettingsBody body = parse(scheme.getBody());
            if (body.enabledPriorities().contains(priorityId) || priorityId.equals(body.defaultPriority())) {
                scheme.replaceBody(serialize(withoutPriority(body, priorityId)));
            }
        }
        for (ProjectSettings ps : projectSettings.findAll()) {
            if (!ps.isCustom()) continue;
            SettingsBody body = parse(ps.getCustomBody());
            if (body.enabledPriorities().contains(priorityId) || priorityId.equals(body.defaultPriority())) {
                ps.customize(serialize(withoutPriority(body, priorityId)));
            }
        }
    }

    private static SettingsBody withoutPriority(SettingsBody body, String priorityId) {
        List<String> enabled = body.enabledPriorities().stream().filter(p -> !p.equals(priorityId)).toList();
        String fallback = priorityId.equals(body.defaultPriority())
                ? (enabled.contains("medium") ? "medium" : enabled.isEmpty() ? "medium" : enabled.get(0))
                : body.defaultPriority();
        return body.withPriorities(enabled, fallback);
    }

    public Optional<SettingsScheme> defaultScheme() { return schemes.findFirstByIsDefaultTrue(); }
}
