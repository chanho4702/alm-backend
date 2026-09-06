package com.platform.almbackend.search.aql;

import java.util.List;

/**
 * AQL 추상 구문 트리 — <b>프론트 {@code store/aql/parser.ts}가 같은 shape을 만들어야 한다</b>.
 * JSON 표현과 예시는 {@code README.md} "AQL" 절에 있고, {@link AqlJson}이 그 표현을 만든다.
 *
 * <p>두 가지를 규칙으로 못박는다 — 안 그러면 두 구현의 AST가 조용히 갈라진다.
 * <ol>
 *   <li><b>필드 이름은 쓴 그대로</b> 담는다. 별칭({@code 상태}→{@code status})·소문자 정규화는
 *       해석 단계({@link AqlResolver})가 한다. 파서는 필드 표를 몰라도 된다.</li>
 *   <li><b>같은 종류의 이항 연산자는 평탄화</b>한다. {@code a AND b AND c}는 자식 셋인 {@code and}
 *       하나이고, 자식이 하나면 감싸지 않는다.</li>
 * </ol>
 */
public final class AqlAst {

    private AqlAst() {}

    /** 질의 하나 — {@code where}가 null이면 조건 없음(전체) */
    public record Query(Node where, List<Order> orderBy) {
        public Query {
            orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        }
    }

    /** 정렬 한 항목. {@code direction}은 {@code "asc"}/{@code "desc"}이고 생략하면 {@code "asc"}다 */
    public record Order(String field, String direction, int position) {}

    public sealed interface Node permits And, Or, Not, Compare, InList, EmptyCheck {}

    public record And(List<Node> children) implements Node {}

    public record Or(List<Node> children) implements Node {}

    public record Not(Node child) implements Node {}

    /**
     * {@code field op value} — {@code op}는 {@code = != ~ !~ < <= > >=} 중 하나.
     *
     * <p>위치를 둘 둔다: 모르는 필드는 필드 자리를, 못 쓰는 연산자는 연산자 자리를
     * 가리켜야 에디터 밑줄이 틀린 곳에 그어진다. 둘 다 JSON에는 실리지 않는다.
     */
    public record Compare(String field, int fieldPosition, String operator, int operatorPosition, Value value)
            implements Node {}

    /** {@code field IN (…)} / {@code field NOT IN (…)} */
    public record InList(String field, int fieldPosition, int operatorPosition, boolean negated,
                         List<Value> values) implements Node {}

    /** {@code field IS EMPTY} / {@code field IS NOT EMPTY} */
    public record EmptyCheck(String field, int fieldPosition, int operatorPosition, boolean negated)
            implements Node {}

    /** 값의 종류 — 해석기가 필드 타입과 함께 보고 의미를 정한다 */
    public enum ValueKind {
        /** 따옴표로 감싼 문자열 */
        STRING,
        /** 따옴표 없는 낱말 — {@code done}, {@code ALM}, {@code 김찬호}, {@code 2026-09-06}, {@code -7d} */
        IDENT,
        /** 낱말 전체가 수 */
        NUMBER,
        /** {@code currentUser()}, {@code startOfMonth(-1)} 같은 함수 호출 */
        FUNCTION
    }

    /**
     * 값 하나. {@code text}는 문자열·낱말·수의 원문이고 함수면 함수 이름이다.
     * {@code args}는 함수일 때만 채워진다.
     */
    public record Value(ValueKind kind, String text, List<Value> args, int position) {
        public Value {
            args = args == null ? List.of() : List.copyOf(args);
        }

        public static Value of(ValueKind kind, String text, int position) {
            return new Value(kind, text, List.of(), position);
        }

        public boolean function(String name) {
            return kind == ValueKind.FUNCTION && text.equalsIgnoreCase(name);
        }
    }
}
