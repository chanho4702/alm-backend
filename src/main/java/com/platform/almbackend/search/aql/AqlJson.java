package com.platform.almbackend.search.aql;

import com.platform.almbackend.search.aql.AqlAst.Node;
import com.platform.almbackend.search.aql.AqlAst.Query;
import com.platform.almbackend.search.aql.AqlAst.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AST의 JSON 표현 — <b>프론트와 서버가 같은 shape을 만든다는 계약</b>이다. 예시는 {@code README.md}의
 * AQL 절(§ 테스트 벡터)에 그대로 실려 있고, 두 구현이 갈라졌는지 확인하는 기준이 된다.
 *
 * <pre>
 * {
 *   "where": {"kind":"and","children":[…]} | null,
 *   "orderBy": [{"field":"due","direction":"asc"}]
 * }
 * </pre>
 *
 * 노드 종류: {@code and}·{@code or}(children) · {@code not}(child) ·
 * {@code compare}(field, operator, value) · {@code in}(field, negated, values) ·
 * {@code empty}(field, negated). 값: {@code {"type":"string|ident|number|function","value":…}}이고
 * 함수는 {@code {"type":"function","name":"currentUser","args":[]}}다.
 */
public final class AqlJson {

    private AqlJson() {}

    public static Map<String, Object> query(Query query) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("where", query.where() == null ? null : node(query.where()));
        List<Map<String, Object>> orders = new ArrayList<>();
        for (AqlAst.Order order : query.orderBy()) {
            // 키 순서까지 계약이다 — 프론트와 문자열로 대조하므로 LinkedHashMap을 쓴다
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", order.field());
            entry.put("direction", order.direction());
            orders.add(entry);
        }
        json.put("orderBy", orders);
        return json;
    }

    public static Map<String, Object> node(Node node) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (node) {
            case AqlAst.And and -> {
                json.put("kind", "and");
                json.put("children", and.children().stream().map(AqlJson::node).toList());
            }
            case AqlAst.Or or -> {
                json.put("kind", "or");
                json.put("children", or.children().stream().map(AqlJson::node).toList());
            }
            case AqlAst.Not not -> {
                json.put("kind", "not");
                json.put("child", node(not.child()));
            }
            case AqlAst.Compare compare -> {
                json.put("kind", "compare");
                json.put("field", compare.field());
                json.put("operator", compare.operator());
                json.put("value", value(compare.value()));
            }
            case AqlAst.InList in -> {
                json.put("kind", "in");
                json.put("field", in.field());
                json.put("negated", in.negated());
                json.put("values", in.values().stream().map(AqlJson::value).toList());
            }
            case AqlAst.EmptyCheck empty -> {
                json.put("kind", "empty");
                json.put("field", empty.field());
                json.put("negated", empty.negated());
            }
        }
        return json;
    }

    public static Map<String, Object> value(Value value) {
        Map<String, Object> json = new LinkedHashMap<>();
        String type = value.kind().name().toLowerCase(Locale.ROOT);
        json.put("type", type);
        if (value.kind() == AqlAst.ValueKind.FUNCTION) {
            json.put("name", value.text());
            json.put("args", value.args().stream().map(AqlJson::value).toList());
        } else {
            json.put("value", value.text());
        }
        return json;
    }
}
