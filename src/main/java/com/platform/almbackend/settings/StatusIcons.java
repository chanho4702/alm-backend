package com.platform.almbackend.settings;

import java.util.Map;

/**
 * 상태 아이콘(프론트 아이콘 맵의 lucide 키) 규칙 한 곳.
 *
 * 상태를 색만으로 구분하면 색각 이상·흑백 인쇄에서 정보가 사라진다 — 모양이 있는 아이콘이
 * 언제나 하나는 있어야 해서, 상태에 아이콘이 지정되지 않았으면 카테고리 의미(kind)의 기본으로 폴백한다.
 */
public final class StatusIcons {
    /** 레지스트리 기본 3종이 심는 값 — V20 마이그레이션의 UPDATE와 같아야 한다 */
    public static final Map<String, String> BUILT_IN = Map.of(
            "todo", "circle",
            "inprogress", "loader-circle",
            "done", "circle-check");

    private static final Map<String, String> BY_KIND = Map.of(
            "new", "circle",
            "active", "refresh-cw",
            "complete", "circle-check");

    public static final int MAX_LENGTH = 40;

    private StatusIcons() {}

    /** 저장값이 비었으면 의미별 기본으로 — 화면이 늘 아이콘 하나는 그릴 수 있게 */
    public static String resolve(String icon, String kind) {
        if (icon != null && !icon.isBlank()) return icon.trim();
        return BY_KIND.getOrDefault(kind, "circle");
    }

    /** 저장 전 정규화. 빈 문자열은 "미지정"이라는 유효한 값이다 */
    public static String normalize(String icon) {
        String trimmed = icon == null ? "" : icon.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("아이콘 키가 너무 깁니다");
        }
        return trimmed;
    }
}
