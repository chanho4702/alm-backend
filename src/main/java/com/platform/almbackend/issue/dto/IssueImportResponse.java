package com.platform.almbackend.issue.dto;

import java.util.List;

/** 가져오기 결과 — 한 줄이 실패해도 나머지는 만들고 사유를 남긴다(전부 롤백 없음) */
public record IssueImportResponse(int created, List<Failure> failed) {
    public record Failure(int row, String title, String reason) {}
}
