package com.platform.almbackend.search.aql;

import com.platform.almbackend.search.aql.AqlAst.Node;
import com.platform.almbackend.search.aql.AqlAst.Order;
import com.platform.almbackend.search.aql.AqlAst.Query;
import com.platform.almbackend.search.aql.AqlAst.Value;
import com.platform.almbackend.search.aql.AqlAst.ValueKind;
import com.platform.almbackend.search.aql.AqlLexer.Token;
import com.platform.almbackend.search.aql.AqlLexer.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 손으로 짠 재귀 하강 파서(외부 파서 라이브러리를 쓰지 않는다). 프론트 {@code store/aql/parser.ts}가
 * 같은 문법·같은 AST를 만든다.
 *
 * <pre>
 * query   := clause? ("ORDER BY" order ("," order)*)?
 * clause  := or
 * or      := and ("OR" and)*
 * and     := term ("AND" term)*          -- AND가 OR보다 강하게 묶인다(JQL과 같음)
 * term    := "NOT" term | "(" clause ")" | cond
 * cond    := field op value
 *          | field ("IN" | "NOT" "IN") ( "(" value ("," value)* ")" | function )
 *          | field ("IS" | "IS" "NOT") "EMPTY"
 * value   := string | number | ident | ident "(" arg? ("," arg)* ")"
 * order   := field ("ASC" | "DESC")?
 * </pre>
 */
public final class AqlParser {

    /** 필드 자리에 올 수 없는 낱말 — 값으로는 쓸 수 있다 */
    private static final Set<String> RESERVED =
            Set.of("and", "or", "not", "in", "is", "empty", "order", "by", "asc", "desc");

    /**
     * 중첩 깊이 상한. 괄호와 {@code NOT}이 둘 다 재귀라 이걸 안 막으면
     * {@code ((((…}가 StackOverflowError로 터져 500으로 새다(리뷰 I1, 깊이 5000 실측).
     */
    private static final int MAX_DEPTH = 50;

    private final List<Token> tokens;
    private int index;
    private int depth;

    private AqlParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Query parse(String input) {
        return new AqlParser(AqlLexer.tokenize(input)).query();
    }

    private Query query() {
        Node where = null;
        if (!peek().is(Type.EOF) && !atOrderBy()) {
            where = or();
        }
        List<Order> orderBy = atOrderBy() ? orderBy() : List.of();
        if (!peek().is(Type.EOF)) {
            throw AqlException.at(peek().position(), "여기서 끝나야 합니다: " + describe(peek()),
                    "AND", "OR", "ORDER BY");
        }
        return new Query(where, orderBy);
    }

    // ── 조건 ──

    private Node or() {
        Node first = and();
        if (!peek().keyword("OR")) return first;
        List<Node> children = new ArrayList<>();
        children.add(first);
        while (peek().keyword("OR")) {
            next();
            children.add(and());
        }
        return new AqlAst.Or(children);
    }

    private Node and() {
        Node first = term();
        if (!peek().keyword("AND")) return first;
        List<Node> children = new ArrayList<>();
        children.add(first);
        while (peek().keyword("AND")) {
            next();
            children.add(term());
        }
        return new AqlAst.And(children);
    }

    private Node term() {
        depth++;
        try {
            return nested();
        } finally {
            depth--;
        }
    }

    private Node nested() {
        if (depth > MAX_DEPTH) {
            throw AqlException.at(peek().position(),
                    "너무 깊게 중첩됐습니다 (최대 " + MAX_DEPTH + "단계)");
        }
        if (peek().keyword("NOT")) {
            next();
            return new AqlAst.Not(term());
        }
        if (peek().is(Type.LPAREN)) {
            next();
            Node inner = or();
            if (!peek().is(Type.RPAREN)) {
                throw AqlException.at(peek().position(), "괄호를 닫아야 합니다", ")");
            }
            next();
            return inner;
        }
        return condition();
    }

    private Node condition() {
        Token field = next();
        if (!field.is(Type.IDENT) || RESERVED.contains(field.text().toLowerCase(java.util.Locale.ROOT))) {
            throw AqlException.at(field.position(), "필드가 필요합니다: " + describe(field), "필드 이름");
        }
        String name = field.text();
        int at = field.position();

        // field NOT IN (…)
        if (peek().keyword("NOT") && peekAhead(1).keyword("IN")) {
            int operatorAt = peek().position();
            next();
            next();
            return new AqlAst.InList(name, at, operatorAt, true, valueList());
        }
        if (peek().keyword("IN")) {
            int operatorAt = next().position();
            return new AqlAst.InList(name, at, operatorAt, false, valueList());
        }
        // field IS [NOT] EMPTY
        if (peek().keyword("IS")) {
            int operatorAt = next().position();
            boolean negated = false;
            if (peek().keyword("NOT")) {
                next();
                negated = true;
            }
            if (!peek().keyword("EMPTY")) {
                throw AqlException.at(peek().position(), "EMPTY가 필요합니다", "EMPTY");
            }
            next();
            return new AqlAst.EmptyCheck(name, at, operatorAt, negated);
        }
        if (!peek().is(Type.OP)) {
            throw AqlException.at(peek().position(), "연산자가 필요합니다",
                    "=", "!=", "~", "!~", "<", "<=", ">", ">=", "IN", "IS");
        }
        Token operatorToken = next();
        return new AqlAst.Compare(name, at, operatorToken.text(), operatorToken.position(), value());
    }

    private List<Value> valueList() {
        if (!peek().is(Type.LPAREN)) {
            // JQL 관례 — 목록을 돌려주는 함수는 괄호 목록 없이 바로 온다: sprint IN openSprints()
            if (peek().is(Type.IDENT) && peekAhead(1).is(Type.LPAREN)) {
                Value single = value();
                if (single.kind() == ValueKind.FUNCTION) return List.of(single);
                throw AqlException.at(single.position(), "여는 괄호가 필요합니다", "(");
            }
            throw AqlException.at(peek().position(), "여는 괄호가 필요합니다", "(");
        }
        next();
        List<Value> values = new ArrayList<>();
        values.add(value());
        while (peek().is(Type.COMMA)) {
            next();
            values.add(value());
        }
        if (!peek().is(Type.RPAREN)) {
            throw AqlException.at(peek().position(), "괄호를 닫아야 합니다", ")", ",");
        }
        next();
        return values;
    }

    private Value value() {
        Token token = peek();
        switch (token.type()) {
            case STRING -> {
                next();
                return Value.of(ValueKind.STRING, token.text(), token.position());
            }
            case NUMBER -> {
                next();
                return Value.of(ValueKind.NUMBER, token.text(), token.position());
            }
            case IDENT -> {
                next();
                // 함수는 낱말에 괄호가 "붙어" 있을 때만 — `status = done (x)`를 함수로 오독하지 않는다
                Token after = peek();
                if (after.is(Type.LPAREN) && after.position() == token.position() + token.text().length()) {
                    next();
                    List<Value> args = new ArrayList<>();
                    if (!peek().is(Type.RPAREN)) {
                        args.add(value());
                        while (peek().is(Type.COMMA)) {
                            next();
                            args.add(value());
                        }
                    }
                    if (!peek().is(Type.RPAREN)) {
                        throw AqlException.at(peek().position(), "괄호를 닫아야 합니다", ")");
                    }
                    next();
                    return new Value(ValueKind.FUNCTION, token.text(), args, token.position());
                }
                return Value.of(ValueKind.IDENT, token.text(), token.position());
            }
            default -> throw AqlException.at(token.position(), "값이 필요합니다", "값");
        }
    }

    // ── 정렬 ──

    private boolean atOrderBy() {
        return peek().keyword("ORDER") && peekAhead(1).keyword("BY");
    }

    private List<Order> orderBy() {
        next();
        next();
        List<Order> orders = new ArrayList<>();
        orders.add(order());
        while (peek().is(Type.COMMA)) {
            next();
            orders.add(order());
        }
        return orders;
    }

    private Order order() {
        Token field = next();
        if (!field.is(Type.IDENT) || RESERVED.contains(field.text().toLowerCase(java.util.Locale.ROOT))) {
            throw AqlException.at(field.position(), "정렬할 필드가 필요합니다: " + describe(field), "필드 이름");
        }
        String direction = "asc";
        if (peek().keyword("ASC")) {
            next();
        } else if (peek().keyword("DESC")) {
            next();
            direction = "desc";
        }
        return new Order(field.text(), direction, field.position());
    }

    // ── 토큰 커서 ──

    private Token peek() {
        return tokens.get(index);
    }

    private Token peekAhead(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    private Token next() {
        Token token = tokens.get(index);
        if (index < tokens.size() - 1) index++;
        return token;
    }

    private static String describe(Token token) {
        return token.is(Type.EOF) ? "입력이 끝났습니다" : token.text();
    }
}
