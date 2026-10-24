package com.platform.almbackend.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<FieldConfig> fields,
        Map<String, List<FieldConfig>> fieldsByType,
        Map<String, TypeWorkflow> workflowsByType) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** kind·color·icon은 레지스트리에서 파생된 읽기 전용 값이다 — 저장하지 않고 읽을 때 다시 채운다 */
    public record WorkflowStatus(String id, String name, String category, int order, String kind, String color,
                                 String icon) {
        public WorkflowStatus stored() { return new WorkflowStatus(id, name, category, order, null, null, null); }
    }

    /**
     * 워크플로 전환(2026-10-24 C1~C3) — from이 비면 전역 전환("모든 상태에서"). 아래 넷은 모두 선택이고, 빈 값이면
     * 그 기능을 쓰지 않는다(구 본문 호환).
     *
     * @param conditions    누가·언제 할 수 있나 — 모두 충족해야 한다({@link Condition})
     * @param validators    전환 직후 값이 있어야 하는 필드 id({@link #VALIDATOR_FIELDS})
     * @param screenFields  전환 화면에서 묻는 칸({@link #SCREEN_FIELDS}). 비면 화면 없이 바로 전환
     * @param postFunctions 전환 뒤 고정 후처리({@link PostFunction}). 해결 설정·해제는 내장이라 항상 돈다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String id, String name, List<String> from, String to,
                             List<Condition> conditions, List<String> validators, List<String> screenFields,
                             List<PostFunction> postFunctions) {
        public Transition(String id, String name, List<String> from, String to) {
            this(id, name, from, to, null, null, null, null);
        }
        public List<String> from() { return from == null ? List.of() : from; }
        public List<Condition> conditions() { return conditions == null ? List.of() : conditions; }
        public List<String> validators() { return validators == null ? List.of() : validators; }
        public List<String> screenFields() { return screenFields == null ? List.of() : screenFields; }
        public List<PostFunction> postFunctions() { return postFunctions == null ? List.of() : postFunctions; }
        /** from이 비면 모든 상태에서 갈 수 있다 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isGlobal() { return from == null || from.isEmpty(); }
        public boolean appliesFrom(String status) { return isGlobal() || from.contains(status); }
        public Transition withFrom(List<String> nextFrom) {
            return new Transition(id, name, nextFrom, to, conditions, validators, screenFields, postFunctions);
        }
    }

    /**
     * 전환 조건 — type: {@code assignee}(담당자만) · {@code role}(프로젝트 역할, role = ADMIN|EDIT) ·
     * {@code subtasksDone}(하위 이슈가 모두 완료 의미 상태)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(String type, String role) {}

    /**
     * 고정 후처리 — type: {@code assignToActor}(담당자를 실행자로) · {@code clearAssignee}(담당자 비우기) ·
     * {@code setResolution}(완료 전환의 기본 해결을 value로, value = DONE|WONT_DO|DUPLICATE|CANNOT_REPRODUCE)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostFunction(String type, String value) {}

    /**
     * 이슈 타입별 워크플로(2026-10-24 C5) — 프로젝트 상태 목록의 <b>부분집합</b>과 그 위의 전환이다. 상태 자체는
     * 프로젝트 목록(statuses)에만 정의되므로 보드 열·완료 판정·리포트는 지금처럼 프로젝트 상태 목록 하나를 본다.
     *
     * @param statuses    이 타입이 쓰는 상태 id. null/빈 목록이면 프로젝트 상태 전부
     * @param transitions null이면 프로젝트 전환 중 이 상태들 사이의 것만, 빈 목록이면 "모두 허용"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypeWorkflow(List<String> statuses, List<Transition> transitions) {}

    /** 이슈 하나에 적용되는 최종 워크플로 — 상태(order 순)와 전환. 전환이 비면 상태 사이 이동이 모두 허용된다 */
    public record ResolvedWorkflow(List<WorkflowStatus> statuses, List<Transition> transitions, boolean typeSpecific) {
        public boolean has(String statusId) { return statuses.stream().anyMatch(s -> s.id().equals(statusId)); }
        public java.util.Optional<WorkflowStatus> status(String statusId) {
            return statuses.stream().filter(s -> s.id().equals(statusId)).findFirst();
        }
    }

    /** 전환 화면에 넣을 수 있는 칸 */
    public static final List<String> SCREEN_FIELDS = List.of("resolution", "comment", "assignee", "fixVersion", "dueDate");

    /** 전환 검증(필수 필드)으로 걸 수 있는 필드 — 첨부·링크·해결·우선순위(항상 값이 있다)는 뺀다 */
    public static final List<String> VALIDATOR_FIELDS = List.of(
            "description", "assignee", "labels", "components", "parent", "sprint", "dueDate", "fixVersion", "estimate");

    public static final Set<String> CONDITION_TYPES = Set.of("assignee", "role", "subtasksDone");
    public static final Set<String> POST_FUNCTION_TYPES = Set.of("assignToActor", "clearAssignee", "setResolution");

    public record Point(double x, double y) {}

    /** 이슈 필드 구성 — 프로젝트·타입·요약·상태는 항상 있으므로 목록에 없다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldConfig(String id, boolean visible, boolean required) {}

    public List<WorkflowStatus> statuses() { return statuses == null ? List.of() : statuses; }
    public List<Transition> transitions() { return transitions == null ? List.of() : transitions; }
    public Map<String, Point> layout() { return layout == null ? Map.of() : layout; }
    public List<String> enabledTypes() { return enabledTypes == null ? List.of() : enabledTypes; }
    public Map<String, TypeWorkflow> workflowsByType() { return workflowsByType == null ? Map.of() : workflowsByType; }

    /**
     * 이 이슈 타입에 적용할 워크플로 — 타입 키가 없으면 프로젝트 기본 워크플로(폴백). 키는 레지스트리 타입 id와
     * 정확히 대조한다(fieldsByType과 같은 규칙).
     */
    public ResolvedWorkflow workflowFor(String typeId) {
        List<WorkflowStatus> all = statuses().stream()
                .sorted(java.util.Comparator.comparingInt(WorkflowStatus::order)).toList();
        TypeWorkflow override = typeId == null ? null : workflowsByType().get(typeId);
        if (override == null) return new ResolvedWorkflow(all, transitions(), false);
        List<String> subset = override.statuses() == null ? List.of() : override.statuses();
        List<WorkflowStatus> statuses = subset.isEmpty() ? all
                : all.stream().filter(s -> subset.contains(s.id())).toList();
        Set<String> ids = new java.util.HashSet<>();
        for (WorkflowStatus s : statuses) ids.add(s.id());
        List<Transition> transitions;
        if (override.transitions() == null) {
            transitions = new ArrayList<>();
            for (Transition t : transitions()) {
                if (!ids.contains(t.to())) continue;
                if (t.isGlobal()) { transitions.add(t); continue; }
                List<String> from = t.from().stream().filter(ids::contains).toList();
                if (!from.isEmpty()) transitions.add(t.withFrom(from));
            }
        } else {
            transitions = override.transitions();
        }
        return new ResolvedWorkflow(statuses, List.copyOf(transitions), true);
    }
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
     * 기준 구성 위에 덮어쓰기를 필드 단위로 얹어 13종 전부로 정규화한다 — 모르는 id는 버리고,
     * 기준에도 덮어쓰기에도 없는 id는 기본값(표시·비필수)으로 채운다. 기준이 13종을 다 갖지 않아도
     * null이 섞이지 않는다. 테스트가 이 경로를 직접 짚어야 해서 package-private이다.
     */
    static List<FieldConfig> overlay(List<FieldConfig> base, List<FieldConfig> overrides) {
        Map<String, FieldConfig> patch = new LinkedHashMap<>();
        for (FieldConfig field : overrides) {
            if (field != null && field.id() != null) patch.putIfAbsent(field.id(), field);
        }
        Map<String, FieldConfig> baseById = indexById(base);
        List<FieldConfig> merged = new ArrayList<>();
        for (String id : FIELD_IDS) {
            FieldConfig field = patch.get(id);
            if (field == null) field = baseById.get(id);
            merged.add(field == null ? new FieldConfig(id, true, false) : field);
        }
        return List.copyOf(merged);
    }

    private static Map<String, FieldConfig> indexById(List<FieldConfig> list) {
        Map<String, FieldConfig> byId = new LinkedHashMap<>();
        for (FieldConfig field : list) byId.put(field.id(), field);
        return byId;
    }

    /**
     * 구버전 본문(필드 구성 없음)이나 일부만 저장된 본문도 13종 전부로 채워 응답한다.
     * 모르는 id는 버리고 순서는 {@link #FIELD_IDS}로 고정한다.
     */
    public List<FieldConfig> fields() {
        if (fields == null || fields.isEmpty()) return defaultFields();
        return overlay(defaultFields(), fields);
    }

    /**
     * 이슈 타입별 덮어쓰기 — 키가 없는 타입은 기본 구성을 따른다. 값은 기본 구성 위에 얹어 13종 전부로
     * 정규화하고, 빈 목록은 "기본 구성 따름"이라 키째 버린다(프론트의 기본 구성 따름 스위치와 같은 뜻).
     * <p>키는 레지스트리 이슈 타입 id와 <b>정확히</b> 대조한다(대소문자 구분). 반면 이슈 생성 요청의
     * {@code type}은 관대하게 받아(공백 제거 + 소문자화) 해석하므로, 키는 레지스트리 id 그대로여야
     * 한다 — 대문자가 섞인 키는 어떤 생성 요청과도 만나지 않고 조용히 무시된다.
     */
    public Map<String, List<FieldConfig>> fieldsByType() {
        if (fieldsByType == null || fieldsByType.isEmpty()) return Map.of();
        List<FieldConfig> base = fields();
        Map<String, List<FieldConfig>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldConfig>> entry : fieldsByType.entrySet()) {
            String typeId = entry.getKey();
            List<FieldConfig> override = entry.getValue();
            if (typeId == null || typeId.isBlank() || override == null || override.isEmpty()) continue;
            normalized.put(typeId, overlay(base, override));
        }
        return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    /** 요청이 보낸 그대로 — 저장 검증용(모르는 id를 잡아야 하므로 정규화 전 값이 필요하다) */
    public List<FieldConfig> rawFields() { return fields == null ? List.of() : fields; }

    /** 요청이 보낸 그대로 — 없는 타입 키·모르는 필드 id를 잡아야 하므로 정규화 전 값이 필요하다 */
    public Map<String, List<FieldConfig>> rawFieldsByType() {
        return fieldsByType == null ? Map.of() : fieldsByType;
    }

    /**
     * 기본 구성 위에 이슈 타입 덮어쓰기를 얹은 최종 구성 — 타입 키가 없으면 기본 그대로.
     * 원본 맵에서 그 타입 하나만 정규화한다(생성 1건마다 맵 전체를 다시 훑지 않기 위해) —
     * 결과는 {@link #fieldsByType()}의 같은 키 값과 항상 같다.
     */
    public List<FieldConfig> resolveFields(String typeId) {
        if (typeId == null || fieldsByType == null) return fields();
        List<FieldConfig> override = fieldsByType.get(typeId);
        if (override == null || override.isEmpty()) return fields();
        return overlay(fields(), override);
    }

    /** 정규화된 13종을 id로 한 번만 펼친다 — 필드를 여러 번 볼 때 재정규화를 피한다 */
    public Map<String, FieldConfig> fieldsById() { return indexById(fields()); }

    /** 이슈 타입으로 해석한 13종 — 생성 시 필수 검사가 쓴다 */
    public Map<String, FieldConfig> fieldsById(String typeId) { return indexById(resolveFields(typeId)); }

    /** 본문을 우선순위만 바꿔 복사 */
    public SettingsBody withPriorities(List<String> enabled, String fallback) {
        return new SettingsBody(statuses(), transitions(), layout(), enabledTypes(), enabled, fallback,
                fields(), fieldsByType(), workflowsByType);
    }

    public static SettingsBody defaults() {
        return new SettingsBody(
                List.of(new WorkflowStatus("todo", "할 일", "todo", 1, null, null, null),
                        new WorkflowStatus("inprogress", "진행 중", "inprogress", 2, null, null, null),
                        new WorkflowStatus("done", "완료", "done", 3, null, null, null)),
                List.of(), Map.of(), List.of("task", "story", "bug", "epic", "subtask"),
                BUILTIN_PRIORITIES, "medium", defaultFields(), Map.of(), Map.of());
    }

    /** 저장용 — 파생 필드(kind/color)를 뺀다 */
    public SettingsBody stored() {
        List<WorkflowStatus> plain = new ArrayList<>();
        for (WorkflowStatus status : statuses()) plain.add(status.stored());
        return new SettingsBody(plain, transitions(), layout(), enabledTypes(), enabledPriorities(), defaultPriority(),
                fields(), fieldsByType(), workflowsByType());
    }
}
