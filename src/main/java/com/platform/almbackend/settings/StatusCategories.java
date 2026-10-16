package com.platform.almbackend.settings;

import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 상태 분류 → 상태 id 해석 규칙 한 벌. AQL {@code statusCategory}와 서버 검색 {@code statusCategories}·
 * {@code openOnly}·집계가 같은 규칙을 쓰도록 여기 모은다 — 둘이 따로 놀면 "상태:진행중"이 화면마다 다른
 * 이슈를 잡는다. 진실은 {@link StatusDef#getCategoryId()}이고, SchemeService가 워크플로 본문과 맞춰 둔다.
 */
public final class StatusCategories {

    public static final String KIND_NEW = "new";
    public static final String KIND_ACTIVE = "active";
    public static final String KIND_COMPLETE = "complete";

    private StatusCategories() {}

    /** 분류 kind(new/active/complete)·id·이름 중 하나로 맞는 분류 id들 — 대소문자 무시 */
    public static List<String> matchingCategoryIds(List<StatusCategory> categories, String text) {
        String value = text == null ? "" : text.trim();
        return categories.stream()
                .filter(c -> c.getKind().equalsIgnoreCase(value)
                        || c.getId().equalsIgnoreCase(value)
                        || c.getName().equalsIgnoreCase(value))
                .map(StatusCategory::getId).toList();
    }

    /** 주어진 분류들에 속한 상태 id. 분류에 상태가 없으면 빈 목록 = "아무것도 아님" */
    public static List<String> statusIdsIn(List<StatusDef> defs, Collection<String> categoryIds) {
        return defs.stream()
                .filter(def -> categoryIds.contains(def.getCategoryId()))
                .map(StatusDef::getId).toList();
    }

    /** kind가 {@code kind}인 분류에 속한 상태 id(예: complete → 완료 계열 전부) */
    public static List<String> statusIdsOfKind(List<StatusCategory> categories, List<StatusDef> defs, String kind) {
        List<String> categoryIds = categories.stream()
                .filter(c -> c.getKind().equalsIgnoreCase(kind))
                .map(StatusCategory::getId).toList();
        return statusIdsIn(defs, categoryIds);
    }

    /** 상태 id → 분류 id. 레지스트리에 없는 상태는 빠진다 */
    public static Map<String, String> categoryOfStatus(List<StatusDef> defs) {
        Map<String, String> map = new HashMap<>();
        for (StatusDef def : defs) map.put(def.getId(), def.getCategoryId());
        return map;
    }

    /** 정렬용 kind 순위 — 할 일(new) < 진행(active) < 완료(complete), 모르는 kind는 맨 뒤 */
    public static int kindRank(String kind) {
        if (kind == null) return 3;
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case KIND_NEW -> 0;
            case KIND_ACTIVE -> 1;
            case KIND_COMPLETE -> 2;
            default -> 3;
        };
    }
}
