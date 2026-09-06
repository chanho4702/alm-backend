package com.platform.almbackend.search.aql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AQL 필드 표 — 정식명·한국어 별칭·타입·쓸 수 있는 연산자. 프론트 자동완성과 목업 평가기가 같은 표를 쓴다
 * ({@code GET /api/alm/issues/query/fields}로 그대로 내려준다).
 *
 * <p>필드명과 별칭은 대소문자를 무시한다.
 */
public final class AqlFields {

    private AqlFields() {}

    /** 필드가 받는 값의 성격 — 연산자 허용과 해석 방법이 여기서 갈린다 */
    public enum Kind {
        /** 이름·id로 하나를 고르는 값(상태·타입·프로젝트·스프린트·버전·해결·컴포넌트) */
        ENUM,
        /** 순서가 있는 값 — {@code <}, {@code >}가 "더 중요/덜 중요"를 뜻한다 */
        ORDERED_ENUM,
        /** 사람 — 이름·이메일·숫자 id·{@code currentUser()} */
        USER,
        /** 여러 값을 담는 컬렉션 */
        MULTI,
        /** 날짜·시각 */
        DATE,
        /** 수 */
        NUMBER,
        /** 문자열 — {@code ~} 포함 검색 */
        TEXT,
        /** 참/거짓 */
        BOOL
    }

    /**
     * @param name         정식 필드명(응답·문서의 기준)
     * @param aliases      한국어 별칭
     * @param kind         값의 성격
     * @param operators    쓸 수 있는 연산자(IN·IS EMPTY 포함, 표시용이자 검증용)
     * @param sortable     ORDER BY에 쓸 수 있는가
     * @param emptyAllowed {@code IS EMPTY}가 의미 있는가(비어 있을 수 있는 필드인가)
     * @param supported    구현되어 있는가 — false면 "아직 지원하지 않습니다"로 거절한다
     */
    public record Field(
            String name,
            List<String> aliases,
            Kind kind,
            List<String> operators,
            boolean sortable,
            boolean emptyAllowed,
            boolean supported) {

        public boolean allows(String operator) {
            return operators.contains(operator);
        }
    }

    private static final List<String> EQ = List.of("=", "!=", "IN", "NOT IN");
    private static final List<String> EQ_EMPTY = List.of("=", "!=", "IN", "NOT IN", "IS EMPTY", "IS NOT EMPTY");
    private static final List<String> ORDERED = List.of("=", "!=", "<", "<=", ">", ">=", "IN", "NOT IN");
    private static final List<String> DATE_OPS =
            List.of("=", "!=", "<", "<=", ">", ">=", "IS EMPTY", "IS NOT EMPTY");
    private static final List<String> NUMBER_OPS =
            List.of("=", "!=", "<", "<=", ">", ">=", "IS EMPTY", "IS NOT EMPTY");
    private static final List<String> TEXT_OPS = List.of("~", "!~", "=", "!=");
    private static final List<String> MATCH_ONLY = List.of("~", "!~");

    private static final List<Field> ALL = List.of(
            new Field("project", List.of("프로젝트"), Kind.ENUM, EQ, false, false, true),
            new Field("key", List.of("키"), Kind.TEXT, List.of("=", "!=", "~", "!~", "IN", "NOT IN"), true, false, true),
            new Field("type", List.of("타입", "유형"), Kind.ENUM, EQ, false, false, true),
            new Field("status", List.of("상태"), Kind.ENUM, EQ, true, false, true),
            new Field("statusCategory", List.of("상태분류"), Kind.ENUM, EQ, false, false, true),
            new Field("priority", List.of("우선순위"), Kind.ORDERED_ENUM, ORDERED, true, false, true),
            new Field("assignee", List.of("담당자", "담당"), Kind.USER, EQ_EMPTY, true, true, true),
            new Field("reporter", List.of("보고자"), Kind.USER, EQ, false, false, true),
            new Field("labels", List.of("라벨"), Kind.MULTI, EQ_EMPTY, false, true, true),
            new Field("component", List.of("컴포넌트"), Kind.MULTI, EQ_EMPTY, false, true, true),
            new Field("sprint", List.of("스프린트"), Kind.ENUM, EQ_EMPTY, false, true, true),
            new Field("fixVersion", List.of("수정버전", "버전"), Kind.ENUM, EQ_EMPTY, false, true, true),
            new Field("resolution", List.of("해결"), Kind.ENUM, EQ_EMPTY, false, true, true),
            new Field("parent", List.of("상위", "상위항목"), Kind.ENUM, EQ_EMPTY, false, true, true),
            new Field("created", List.of("생성일"), Kind.DATE, DATE_OPS, true, false, true),
            new Field("updated", List.of("수정일"), Kind.DATE, DATE_OPS, true, false, true),
            new Field("due", List.of("마감일"), Kind.DATE, DATE_OPS, true, true, true),
            // 해결 시각을 저장하는 컬럼이 아직 없다 — 있는 척하고 다른 값으로 답하지 않는다
            new Field("resolved", List.of("해결일"), Kind.DATE, DATE_OPS, false, false, false),
            new Field("estimate", List.of("예상시간"), Kind.NUMBER, NUMBER_OPS, true, true, true),
            new Field("text", List.of("텍스트", "내용"), Kind.TEXT, MATCH_ONLY, false, false, true),
            new Field("summary", List.of("요약", "제목"), Kind.TEXT, TEXT_OPS, true, false, true),
            new Field("archived", List.of("보관"), Kind.BOOL, List.of("=", "!="), false, false, true));

    private static final Map<String, Field> INDEX = index();

    private static Map<String, Field> index() {
        Map<String, Field> map = new LinkedHashMap<>();
        for (Field field : ALL) {
            map.put(lower(field.name()), field);
            for (String alias : field.aliases()) map.put(lower(alias), field);
        }
        return Map.copyOf(map);
    }

    public static List<Field> all() {
        return ALL;
    }

    /** 정렬 가능한 필드의 정식명 */
    public static List<String> sortable() {
        return ALL.stream().filter(Field::sortable).map(Field::name).toList();
    }

    /**
     * 별칭·대소문자를 정규화해 필드를 찾는다. 모르면 {@link AqlException}(400)이다 —
     * 오타를 조용히 무시하면 "왜 결과가 이상하지"로 끝난다.
     */
    public static Field require(String written, int position) {
        Field field = INDEX.get(lower(written));
        if (field == null) {
            throw AqlException.at(position, "필드를 모릅니다: " + written);
        }
        if (!field.supported()) {
            throw AqlException.at(position, "아직 지원하지 않는 필드입니다: " + written);
        }
        return field;
    }


    /**
     * 이 필드에 이 연산자를 쓸 수 있는가. 실행 전(검증 엔드포인트)과 실행 중이 같은 규칙을 쓰도록
     * 여기 한 곳에만 둔다.
     */
    public static void requireOperator(Field field, String operator, int position) {
        if (field.allows(operator)) return;
        if (operator.equals("~") || operator.equals("!~")) {
            throw AqlException.at(position, "'" + operator + "'는 텍스트 필드에만 쓸 수 있습니다 (" + field.name() + ")");
        }
        if (operator.equals("<") || operator.equals("<=") || operator.equals(">") || operator.equals(">=")) {
            throw AqlException.at(position,
                    "'" + operator + "'는 날짜·숫자 필드에만 쓸 수 있습니다 (" + field.name() + ")");
        }
        if (field.operators().equals(MATCH_ONLY)) {
            throw AqlException.at(position,
                    "'" + operator + "'는 " + field.name() + " 필드에 쓸 수 없습니다 — 포함 검색은 '~'입니다", "~");
        }
        throw AqlException.at(position, "'" + operator + "'는 " + field.name() + " 필드에 쓸 수 없습니다",
                field.operators().toArray(String[]::new));
    }

    private static String lower(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
