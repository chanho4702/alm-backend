package com.platform.almbackend.search.aql;

import java.util.ArrayList;
import java.util.List;

/**
 * AQL 토크나이저 — 손으로 짠다(외부 파서 라이브러리를 쓰지 않는다). 프론트 {@code store/aql/lexer.ts}가
 * 같은 규칙을 그대로 구현해야 한다.
 *
 * <p>토큰은 다섯 가지다.
 * <ul>
 *   <li>{@code STRING} — {@code "…"} 또는 {@code '…'}. 안에서 {@code \"}{@code \\}로 이스케이프한다.</li>
 *   <li>{@code NUMBER} — 낱말 전체가 {@code [+-]?\d+(\.\d+)?}일 때만. {@code -7d}·{@code 2026-09-06}은 낱말이라 IDENT다.</li>
 *   <li>{@code IDENT} — 따옴표 없는 낱말. 글자(한글 포함)·숫자·{@code _ . - + @ /}로 이어진다.</li>
 *   <li>{@code OP} — {@code = != ~ !~ < <= > >=}</li>
 *   <li>{@code LPAREN} {@code RPAREN} {@code COMMA} {@code EOF}</li>
 * </ul>
 *
 * <p>키워드(AND/OR/NOT/IN/IS/EMPTY/ORDER/BY/ASC/DESC)는 별도 토큰이 아니라 IDENT다 — 대소문자 무시
 * 판정은 파서가 한다. 그래야 {@code labels = and} 같은 값도 자연스럽게 쓸 수 있다.
 */
public final class AqlLexer {

    private AqlLexer() {}

    public enum Type { IDENT, STRING, NUMBER, OP, LPAREN, RPAREN, COMMA, EOF }

    /** 한 토큰 — {@code position}은 0부터 세는 입력 오프셋(오류 밑줄의 근거) */
    public record Token(Type type, String text, int position) {

        public boolean is(Type type) {
            return this.type == type;
        }

        /** 키워드 비교는 언제나 대소문자 무시 */
        public boolean keyword(String word) {
            return type == Type.IDENT && text.equalsIgnoreCase(word);
        }
    }

    public static List<Token> tokenize(String input) {
        String source = input == null ? "" : input;
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            switch (c) {
                case '(' -> { tokens.add(new Token(Type.LPAREN, "(", i)); i++; }
                case ')' -> { tokens.add(new Token(Type.RPAREN, ")", i)); i++; }
                case ',' -> { tokens.add(new Token(Type.COMMA, ",", i)); i++; }
                case '"', '\'' -> i = readString(source, i, tokens);
                case '=' -> {
                    // `==`는 JQL에도 AQL에도 없다 — 시작 위치를 짚어 준다
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        throw AqlException.at(i, "연산자를 모릅니다: ==", "=", "!=");
                    }
                    tokens.add(new Token(Type.OP, "=", i));
                    i++;
                }
                case '!' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.OP, "!=", i));
                        i += 2;
                    } else if (i + 1 < source.length() && source.charAt(i + 1) == '~') {
                        tokens.add(new Token(Type.OP, "!~", i));
                        i += 2;
                    } else {
                        throw AqlException.at(i, "연산자를 모릅니다: !", "!=", "!~");
                    }
                }
                case '~' -> { tokens.add(new Token(Type.OP, "~", i)); i++; }
                case '<', '>' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.OP, c + "=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Type.OP, String.valueOf(c), i));
                        i++;
                    }
                }
                default -> {
                    if (!isWordChar(c)) {
                        throw AqlException.at(i, "알 수 없는 문자입니다: " + c);
                    }
                    int start = i;
                    while (i < source.length() && isWordChar(source.charAt(i))) i++;
                    String word = source.substring(start, i);
                    tokens.add(new Token(isNumber(word) ? Type.NUMBER : Type.IDENT, word, start));
                }
            }
        }
        tokens.add(new Token(Type.EOF, "", source.length()));
        return tokens;
    }

    /** 낱말 문자 — 한글·한자도 {@link Character#isLetter}가 참이라 별도 범위를 두지 않는다 */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '+' || c == '@' || c == '/';
    }

    /** 낱말 전체가 수일 때만 NUMBER — {@code -7d}, {@code 2026-09-06}은 여기서 걸러져 IDENT가 된다 */
    private static boolean isNumber(String word) {
        return word.matches("[+-]?\\d+(\\.\\d+)?");
    }

    private static int readString(String source, int start, List<Token> tokens) {
        char quote = source.charAt(start);
        StringBuilder text = new StringBuilder();
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                text.append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                tokens.add(new Token(Type.STRING, text.toString(), start));
                return i + 1;
            }
            text.append(c);
            i++;
        }
        throw AqlException.at(start, "따옴표를 닫아야 합니다", String.valueOf(quote));
    }
}
