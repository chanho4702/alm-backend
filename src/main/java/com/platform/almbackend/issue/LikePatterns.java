package com.platform.almbackend.issue;

/**
 * LIKE 패턴 이스케이프 — 사용자 입력의 {@code %}·{@code _}·{@code \}를 글자 그대로 찾게 한다.
 * 이스케이프 문자를 질의에 명시해야 DB 기본값(PG는 {@code \}, H2도 {@code \})에 기대지 않는다.
 */
public final class LikePatterns {
    public static final char ESCAPE = '\\';

    private LikePatterns() {}

    public static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') escaped.append(ESCAPE);
            escaped.append(c);
        }
        return escaped.toString();
    }
}
