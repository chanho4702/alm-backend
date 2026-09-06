package com.platform.almbackend.search.aql;

import com.platform.almbackend.search.aql.AqlAst.Node;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DB·org 없이 할 수 있는 검사 — 필드가 있는가, 그 필드에 이 연산자를 쓸 수 있는가, 정렬할 수 있는 필드인가.
 * 에디터의 실시간 검증({@code POST /api/alm/issues/query/validate})이 이것만 돌린다.
 *
 * <p>값 해석(상태 이름이 진짜 있는지, 그런 사용자가 있는지)은 <b>하지 않는다</b> — 한 글자 칠 때마다
 * 레지스트리와 org를 두드릴 이유가 없다. 그래서 검증을 통과한 질의가 실행에서 400이 날 수 있다.
 */
final class AqlValidation {

    /** 절 수 상한 — 평탄한 AND는 재귀가 아니라 깊이 제한에 안 걸린다(리뷰 I1, 5만 절 실측) */
    private static final int MAX_CLAUSES = 200;

    private AqlValidation() {}

    /** 검사하면서 쓰인 필드의 정식명을 모은다(에디터가 힌트에 쓴다) */
    static Set<String> check(AqlAst.Query query) {
        Set<String> used = new LinkedHashSet<>();
        walk(query.where(), used, new int[1]);
        for (AqlAst.Order order : query.orderBy()) {
            AqlFields.Field field = AqlFields.require(order.field(), order.position());
            if (!field.sortable()) {
                throw AqlException.at(order.position(), "정렬할 수 없는 필드입니다: " + field.name(),
                        AqlFields.sortable().toArray(String[]::new));
            }
            used.add(field.name());
        }
        return used;
    }

    private static void walk(Node node, Set<String> used, int[] clauses) {
        switch (node) {
            case null -> { }
            case AqlAst.And and -> and.children().forEach(child -> walk(child, used, clauses));
            case AqlAst.Or or -> or.children().forEach(child -> walk(child, used, clauses));
            case AqlAst.Not not -> walk(not.child(), used, clauses);
            case AqlAst.Compare compare -> used.add(require(compare.field(), compare.fieldPosition(),
                    compare.operator(), compare.operatorPosition(), clauses));
            case AqlAst.InList in -> used.add(require(in.field(), in.fieldPosition(),
                    in.negated() ? "NOT IN" : "IN", in.operatorPosition(), clauses));
            case AqlAst.EmptyCheck empty -> used.add(require(empty.field(), empty.fieldPosition(),
                    empty.negated() ? "IS NOT EMPTY" : "IS EMPTY", empty.operatorPosition(), clauses));
        }
    }

    /** 모르는 필드는 필드 자리, 못 쓰는 연산자는 연산자 자리를 짚는다 */
    private static String require(String written, int fieldPosition, String operator, int operatorPosition,
                                  int[] clauses) {
        if (++clauses[0] > MAX_CLAUSES) {
            throw AqlException.at(fieldPosition, "조건이 너무 많습니다 (최대 " + MAX_CLAUSES + "개)");
        }
        AqlFields.Field field = AqlFields.require(written, fieldPosition);
        AqlFields.requireOperator(field, operator, operatorPosition);
        return field.name();
    }
}
