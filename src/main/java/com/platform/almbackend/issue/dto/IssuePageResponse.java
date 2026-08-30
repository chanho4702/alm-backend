package com.platform.almbackend.issue.dto;

import java.util.List;

/** 서버 검색·페이징 응답 — total은 필터를 적용한 전체 건수 */
public record IssuePageResponse(List<IssueResponse> items, int page, int size, long total) {}
