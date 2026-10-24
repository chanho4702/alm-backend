package com.platform.almbackend.dev;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 커밋 메시지에서 이슈 키를 찾는다(V41 커밋 연결). <b>등록된 프로젝트 키만</b> 키로 인정한다 — {@code UTF-8}·{@code ISO-8601}
 * 같은 낱말이 이슈로 오인되지 않게.
 *
 * <ul>
 *   <li>대소문자 무시: {@code plat-12} → {@code PLAT-12}. 번호 앞 0은 지운다({@code PLAT-012} → {@code PLAT-12}).</li>
 *   <li>둘레: 영숫자·{@code _}에 붙어 있으면 키가 아니다({@code xPLAT-12}, {@code PLAT-12a}, {@code PLAT_12}). 괄호·대괄호·콜론·
 *       슬래시·공백 등은 경계다 — {@code [PLAT-12]}, {@code (PLAT-12)}, {@code Refs: PLAT-12}, {@code fixes PLAT-12},
 *       {@code feat/PLAT-12-login}.</li>
 *   <li>프로젝트 키에는 {@code -}·{@code _}가 들어갈 수 있다({@code [A-Za-z][A-Za-z0-9_-]{1,11}}). 그래서 {@code -}로 이어진 접두를
 *       통째로 잡은 뒤 <b>가장 긴 등록 키</b>를 뒤쪽 조각에서 고른다: {@code MY-APP-3}은 {@code MY-APP}이 등록돼 있으면 그것,
 *       아니면 {@code APP}. {@code feature-PLAT-12}도 {@code PLAT-12}다.</li>
 *   <li>번호는 1~9자리. 한 메시지에서 중복은 한 번, 최대 {@link #MAX_KEYS}개(나온 순서).</li>
 * </ul>
 */
public final class IssueKeyParser {

    public static final int MAX_KEYS = 20;

    /** 접두(하이픈으로 이어진 조각들) + "-" + 번호. 앞뒤가 영숫자·_이면 키가 아니다 */
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z][A-Za-z0-9_]*)*)-(\\d{1,9})(?![A-Za-z0-9_])");

    private IssueKeyParser() {}

    /**
     * @param message     커밋 메시지 전체(제목 + 본문)
     * @param projectKeys 등록된 프로젝트 키(대문자)
     * @return 정규화된 이슈 키(대문자, 번호 앞 0 없음) — 나온 순서
     */
    public static List<String> parse(String message, Set<String> projectKeys) {
        if (message == null || message.isEmpty() || projectKeys.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CANDIDATE.matcher(message);
        while (m.find() && out.size() < MAX_KEYS) {
            String projectKey = longestRegisteredSuffix(m.group(1), projectKeys);
            if (projectKey == null) continue;
            long number = Long.parseLong(m.group(2));
            if (number <= 0) continue;
            out.add(projectKey + "-" + number);
        }
        return List.copyOf(out);
    }

    /** "feature-MY-APP" → 뒤쪽 조각 묶음을 길이 순으로("FEATURE-MY-APP", "MY-APP", "APP") 맞춰 본다 */
    private static String longestRegisteredSuffix(String prefix, Set<String> projectKeys) {
        String upper = prefix.toUpperCase(Locale.ROOT);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < upper.length(); i++) {
            if (upper.charAt(i) == '-') starts.add(i + 1);
        }
        for (int start : starts) {
            String candidate = upper.substring(start);
            if (candidate.length() <= 12 && projectKeys.contains(candidate)) return candidate;
        }
        return null;
    }
}
