package com.platform.almbackend.planning;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 해석된 프로젝트 계획 설정(V39) — 행이 없으면 {@link #DEFAULTS}(기존 동작 그대로: 자동 생성 끔·이월 백로그·척도 linear).
 *
 * <p>추정 척도는 {@code story_points}의 <b>허용 값</b>만 정한다. 저장·합계·속도는 늘 숫자다 — T셔츠는 크기 → 포인트 매핑이다.
 * {@code linear}는 제한 없는 수직선(0~9999, 소수 첫째 자리, V30 그대로)이라 {@link #allowedPoints()}가 null이다.
 */
public record PlanningSettings(
        boolean autoCreateNext,
        int lengthDays,
        int cooldownDays,
        String defaultCarryOver,
        String scale,
        List<BigDecimal> customValues,
        Map<String, BigDecimal> tshirtPoints,
        int version) {

    public static final List<String> SCALES = List.of("fibonacci", "tshirt", "linear", "custom");
    public static final List<String> CARRY_OVER = List.of("next", "backlog");
    public static final List<String> TSHIRT_SIZES = List.of("XS", "S", "M", "L", "XL");
    public static final List<BigDecimal> FIBONACCI = Stream.of(0, 1, 2, 3, 5, 8, 13, 21).map(BigDecimal::valueOf).toList();
    public static final Map<String, BigDecimal> TSHIRT_DEFAULT = tshirt(1, 2, 3, 5, 8);

    public static final PlanningSettings DEFAULTS =
            new PlanningSettings(false, 14, 0, "backlog", "linear", List.of(), TSHIRT_DEFAULT, 0);

    /** 이 척도에서 고를 수 있는 포인트(오름차순). null = 제한 없음(linear) */
    public List<BigDecimal> allowedPoints() {
        return switch (scale) {
            case "fibonacci" -> FIBONACCI;
            case "tshirt" -> List.copyOf(tshirtPoints.values());
            case "custom" -> customValues;
            default -> null;
        };
    }

    public boolean allows(BigDecimal points) {
        List<BigDecimal> allowed = allowedPoints();
        return points == null || allowed == null || allowed.stream().anyMatch(v -> v.compareTo(points) == 0);
    }

    public String scaleLabel() {
        return switch (scale) {
            case "fibonacci" -> "피보나치";
            case "tshirt" -> "T셔츠";
            case "custom" -> "사용자 지정";
            default -> "선형";
        };
    }

    private static Map<String, BigDecimal> tshirt(int xs, int s, int m, int l, int xl) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("XS", BigDecimal.valueOf(xs));
        map.put("S", BigDecimal.valueOf(s));
        map.put("M", BigDecimal.valueOf(m));
        map.put("L", BigDecimal.valueOf(l));
        map.put("XL", BigDecimal.valueOf(xl));
        return java.util.Collections.unmodifiableMap(map);
    }
}
