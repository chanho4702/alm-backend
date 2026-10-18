package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 서버 검색·페이징 응답 — total은 필터를 적용한 전체 건수.
 * {@code truncated}는 이 페이지 뒤에 결과가 더 있다는 뜻이다(2026-10-18 추가, 하위 호환) — 한 페이지만 읽는
 * 화면(하위 이슈·자식 목록)이 "N건 중 M건"과 "더 보기"를 그리게 한다. 조용히 잘린 목록을 전체로 오인하지 않게.
 */
public record IssuePageResponse(
        List<IssueResponse> items,
        int page,
        int size,
        long total,
        @Schema(description = "이 페이지 뒤에 결과가 더 있다(page·size로 다음 페이지를 읽는다)")
        boolean truncated) {

    public static IssuePageResponse of(List<IssueResponse> items, int page, int size, long total) {
        return new IssuePageResponse(items, page, size, total, (long) page * size + items.size() < total);
    }
}
