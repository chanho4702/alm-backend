package com.platform.almbackend.search.aql;

/**
 * AQL 문법 검사의 공개 입구 — 검색 밖(저장 필터 등)에서 "이 문자열이 AQL로 말이 되는가"만 물을 때 쓴다.
 *
 * <p>보는 것과 보지 않는 것은 {@code POST /api/alm/issues/query/validate}와 똑같다: 문법·필드·연산자는
 * 보고, 값이 실재하는지(그런 상태·사용자가 있는지)는 보지 않는다. 실패는 {@link AqlException}이라
 * {@link AqlExceptionHandler}가 400 {@code {error, position, expected}}로 내보낸다 — 저장 실패와
 * 검색 실패가 같은 모양이라 프론트 에디터가 한 가지 처리만 하면 된다.
 */
public final class AqlSyntax {

    private AqlSyntax() {}

    public static void requireValid(String aql) {
        AqlValidation.check(AqlParser.parse(aql == null ? "" : aql));
    }
}
