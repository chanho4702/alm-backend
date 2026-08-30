package com.platform.almbackend.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
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
        List<String> enabledTypes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowStatus(String id, String name, String category, int order, String kind, String color) {
        public WorkflowStatus stored() { return new WorkflowStatus(id, name, category, order, null, null); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String id, String name, List<String> from, String to) {}

    public record Point(double x, double y) {}

    public List<WorkflowStatus> statuses() { return statuses == null ? List.of() : statuses; }
    public List<Transition> transitions() { return transitions == null ? List.of() : transitions; }
    public Map<String, Point> layout() { return layout == null ? Map.of() : layout; }
    public List<String> enabledTypes() { return enabledTypes == null ? List.of() : enabledTypes; }

    public static SettingsBody defaults() {
        return new SettingsBody(
                List.of(new WorkflowStatus("todo", "할 일", "todo", 1, null, null),
                        new WorkflowStatus("inprogress", "진행 중", "inprogress", 2, null, null),
                        new WorkflowStatus("done", "완료", "done", 3, null, null)),
                List.of(), Map.of(), List.of("task", "story", "bug", "epic", "subtask"));
    }

    /** 저장용 — 파생 필드(kind/color)를 뺀다 */
    public SettingsBody stored() {
        List<WorkflowStatus> plain = new ArrayList<>();
        for (WorkflowStatus status : statuses()) plain.add(status.stored());
        return new SettingsBody(plain, transitions(), layout(), enabledTypes());
    }
}
