package com.platform.almbackend.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 보기의 표시 옵션 — Linear Display options 패턴(그룹·하위 그룹·정렬·표시 속성·하위 이슈·빈 그룹). 값은 닫힌 어휘다:
 * 모르는 값을 저장해 두면 화면이 조용히 기본값으로 그려 "저장했는데 안 바뀐다"가 된다. 그래서 저장 때 400으로 막는다.
 *
 * <p>요청에서 비운 항목은 기본값으로 채워 <b>언제나 모든 항목이 있는</b> 모양으로 저장·응답한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "표시 옵션. 비운 항목은 기본값으로 채워 응답한다")
public record ViewDisplay(
        @Schema(description = "그룹 기준 — none·status·assignee·priority·sprint·epic·label·type·component·fixVersion", example = "status")
        String groupBy,
        @Schema(description = "하위 그룹 기준(목록 레이아웃) — groupBy와 같은 어휘, groupBy와 같을 수 없다", example = "none")
        String subGroupBy,
        @Schema(description = "정렬 기준 — manual(랭크)·priority·status·updated·created·due·startDate·storyPoints·estimate·title·key", example = "priority")
        String orderBy,
        @Schema(description = "정렬 방향 asc·desc", example = "desc")
        String orderDirection,
        @Schema(description = "표시 속성(목록 열·카드 필드) — 순서 유지", example = "[\"key\", \"status\", \"assignee\", \"storyPoints\"]")
        List<String> properties,
        @Schema(description = "하위 이슈 표시", example = "true")
        Boolean showSubIssues,
        @Schema(description = "빈 그룹 표시", example = "false")
        Boolean showEmptyGroups) {

    public static final List<String> GROUPS = List.of(
            "none", "status", "assignee", "priority", "sprint", "epic", "label", "type", "component", "fixVersion");
    public static final List<String> ORDERS = List.of(
            "manual", "priority", "status", "updated", "created", "due", "startDate", "storyPoints", "estimate",
            "title", "key");
    public static final List<String> PROPERTIES = List.of(
            "key", "type", "status", "priority", "assignee", "reporter", "labels", "components", "sprint", "fixVersion",
            "due", "startDate", "storyPoints", "estimate", "flagged", "parent", "created", "updated");
    public static final List<String> DEFAULT_PROPERTIES = List.of(
            "key", "type", "status", "priority", "assignee", "labels", "flagged");

    /** 검증 + 기본값 채우기. null이면 전부 기본값 */
    public static ViewDisplay normalize(ViewDisplay requested) {
        ViewDisplay in = requested == null ? new ViewDisplay(null, null, null, null, null, null, null) : requested;
        String groupBy = pick(in.groupBy(), GROUPS, "none", "그룹 기준");
        String subGroupBy = pick(in.subGroupBy(), GROUPS, "none", "하위 그룹 기준");
        if (!"none".equals(subGroupBy) && subGroupBy.equals(groupBy)) {
            throw new IllegalArgumentException("하위 그룹 기준은 그룹 기준과 달라야 합니다");
        }
        if ("none".equals(groupBy) && !"none".equals(subGroupBy)) {
            throw new IllegalArgumentException("하위 그룹은 그룹 기준이 있을 때만 쓸 수 있습니다");
        }
        String orderBy = pick(in.orderBy(), ORDERS, "manual", "정렬 기준");
        String direction = pick(in.orderDirection(), List.of("asc", "desc"), "asc", "정렬 방향");
        List<String> properties;
        if (in.properties() == null) {
            properties = DEFAULT_PROPERTIES;
        } else {
            if (in.properties().size() > PROPERTIES.size()) throw new IllegalArgumentException("표시 속성이 너무 많습니다");
            Set<String> seen = new LinkedHashSet<>();
            for (String property : in.properties()) {
                if (property == null || !PROPERTIES.contains(property)) {
                    throw new IllegalArgumentException("모르는 표시 속성입니다: " + property);
                }
                seen.add(property);
            }
            properties = List.copyOf(new ArrayList<>(seen));
        }
        return new ViewDisplay(groupBy, subGroupBy, orderBy, direction, properties,
                in.showSubIssues() == null || in.showSubIssues(),
                in.showEmptyGroups() != null && in.showEmptyGroups());
    }

    private static String pick(String value, List<String> allowed, String fallback, String label) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        if (!allowed.contains(trimmed)) throw new IllegalArgumentException("모르는 " + label + "입니다: " + trimmed);
        return trimmed;
    }
}
