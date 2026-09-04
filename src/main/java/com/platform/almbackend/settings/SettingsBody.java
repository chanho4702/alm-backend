package com.platform.almbackend.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스킴/커스텀 본문 — 프론트 `SettingsBody`와 같은 JSON 형태. 상태는 레지스트리 참조(id) + 캐시(name/category),
 * 읽을 때는 레지스트리 값으로 다시 채운다(kind/color 파생).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettingsBody(
        List<WorkflowStatus> statuses,
        List<Transition> transitions,
        Map<String, Point> layout,
        List<String> enabledTypes,
        List<String> enabledPriorities,
        String defaultPriority,
        List<FieldConfig> fields) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** kind·color·icon은 레지스트리에서 파생된 읽기 전용 값이다 — 저장하지 않고 읽을 때 다시 채운다 */
    public record WorkflowStatus(String id, String name, String category, int order, String kind, String color,
                                 String icon) {
        public WorkflowStatus stored() { return new WorkflowStatus(id, name, category, order, null, null, null); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String id, String name, List<String> from, String to) {}

    public record Point(double x, double y) {}

    /** 이슈 필드 구성 — 프로젝트·타입·요약·상태는 항상 있으므로 목록에 없다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldConfig(String id, boolean visible, boolean required) {}

    public List<WorkflowStatus> statuses() { return statuses == null ? List.of() : statuses; }
    public List<Transition> transitions() { return transitions == null ? List.of() : transitions; }
    public Map<String, Point> layout() { return layout == null ? Map.of() : layout; }
    public List<String> enabledTypes() { return enabledTypes == null ? List.of() : enabledTypes; }
    /** 구버전 본문(우선순위 필드 없음)은 기본 5종 전부 활성 */
    public List<String> enabledPriorities() {
        return enabledPriorities == null ? BUILTIN_PRIORITIES : enabledPriorities;
    }
    public String defaultPriority() { return defaultPriority == null || defaultPriority.isBlank() ? "medium" : defaultPriority; }
    public static final List<String> BUILTIN_PRIORITIES = List.of("highest", "high", "medium", "low", "lowest");

    // ── 이슈 필드 구성 ──

    /** 구성 가능한 필드 13종 — 모달의 지라 필드 순서 고정 */
    public static final List<String> FIELD_IDS = List.of(
            "description", "assignee", "priority", "labels", "components", "parent",
            "sprint", "dueDate", "fixVersion", "resolution", "estimate", "attachments", "links");

    /** 오류 메시지용 한국어 이름 */
    public static final Map<String, String> FIELD_NAMES = Map.ofEntries(
            Map.entry("description", "설명"),
            Map.entry("assignee", "담당자"),
            Map.entry("priority", "우선순위"),
            Map.entry("labels", "라벨"),
            Map.entry("components", "컴포넌트"),
            Map.entry("parent", "상위 항목"),
            Map.entry("sprint", "스프린트"),
            Map.entry("dueDate", "마감일"),
            Map.entry("fixVersion", "수정 버전"),
            Map.entry("resolution", "해결"),
            Map.entry("estimate", "예상 시간"),
            Map.entry("attachments", "첨부"),
            Map.entry("links", "링크"));

    /** 완료 상태에서만 의미가 있어 필수로 지정할 수 없다 */
    public static final String RESOLUTION = "resolution";

    /** 프로젝트 단위 구성이라 필수로 걸면 최상위 이슈를 만들 수 없다 */
    public static final String PARENT = "parent";

    public static String fieldName(String id) { return FIELD_NAMES.getOrDefault(id, id); }

    /** 생성 시 필수 위반 메시지 — 프론트 목업과 같은 문구("담당자는 필수입니다") */
    public static String requiredMessage(String id) {
        String name = fieldName(id);
        char last = name.charAt(name.length() - 1);
        boolean hasFinalConsonant = last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
        return name + (hasFinalConsonant ? "은" : "는") + " 필수입니다";
    }

    /** 13종 전부 표시·비필수 */
    public static List<FieldConfig> defaultFields() {
        List<FieldConfig> defaults = new ArrayList<>();
        for (String id : FIELD_IDS) defaults.add(new FieldConfig(id, true, false));
        return List.copyOf(defaults);
    }

    /**
     * 구버전 본문(필드 구성 없음)이나 일부만 저장된 본문도 13종 전부로 채워 응답한다.
     * 모르는 id는 버리고 순서는 {@link #FIELD_IDS}로 고정한다.
     */
    public List<FieldConfig> fields() {
        if (fields == null || fields.isEmpty()) return defaultFields();
        Map<String, FieldConfig> byId = new LinkedHashMap<>();
        for (FieldConfig field : fields) {
            if (field != null && field.id() != null) byId.putIfAbsent(field.id(), field);
        }
        List<FieldConfig> merged = new ArrayList<>();
        for (String id : FIELD_IDS) {
            FieldConfig field = byId.get(id);
            merged.add(field == null ? new FieldConfig(id, true, false) : field);
        }
        return List.copyOf(merged);
    }

    /** 요청이 보낸 그대로 — 저장 검증용(모르는 id를 잡아야 하므로 정규화 전 값이 필요하다) */
    public List<FieldConfig> rawFields() { return fields == null ? List.of() : fields; }

    /** 정규화된 13종을 id로 한 번만 펼친다 — 필드를 여러 번 볼 때 재정규화를 피한다 */
    public Map<String, FieldConfig> fieldsById() {
        Map<String, FieldConfig> byId = new LinkedHashMap<>();
        for (FieldConfig field : fields()) byId.put(field.id(), field);
        return byId;
    }

    public FieldConfig field(String id) {
        for (FieldConfig field : fields()) {
            if (field.id().equals(id)) return field;
        }
        return new FieldConfig(id, true, false);
    }

    public boolean isFieldRequired(String id) { return field(id).required(); }

    /** 본문을 우선순위만 바꿔 복사 */
    public SettingsBody withPriorities(List<String> enabled, String fallback) {
        return new SettingsBody(statuses(), transitions(), layout(), enabledTypes(), enabled, fallback, fields());
    }

    public static SettingsBody defaults() {
        return new SettingsBody(
                List.of(new WorkflowStatus("todo", "할 일", "todo", 1, null, null, null),
                        new WorkflowStatus("inprogress", "진행 중", "inprogress", 2, null, null, null),
                        new WorkflowStatus("done", "완료", "done", 3, null, null, null)),
                List.of(), Map.of(), List.of("task", "story", "bug", "epic", "subtask"),
                BUILTIN_PRIORITIES, "medium", defaultFields());
    }

    /** 저장용 — 파생 필드(kind/color)를 뺀다 */
    public SettingsBody stored() {
        List<WorkflowStatus> plain = new ArrayList<>();
        for (WorkflowStatus status : statuses()) plain.add(status.stored());
        return new SettingsBody(plain, transitions(), layout(), enabledTypes(), enabledPriorities(), defaultPriority(), fields());
    }
}
