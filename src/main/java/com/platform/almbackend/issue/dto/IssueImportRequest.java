package com.platform.almbackend.issue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 이관·CSV 가져오기 — 항목마다 키를 보존할 수 있다(`{프로젝트키}-{번호}`, 유일).
 * 한 요청은 최대 {@value #MAX_ITEMS}건이다(감사 A-14) — 행마다 트랜잭션·프로젝트 잠금을 잡으므로 요청 하나가
 * 끝없이 길게 돌며 같은 프로젝트의 생성을 줄 세우지 않게 한다. 더 많으면 나눠 보낸다. 본문 크기 상한은
 * {@code ImportBodyLimitFilter}가 따로 본다.
 */
public record IssueImportRequest(
        @NotEmpty(message = "가져올 이슈가 없습니다")
        @Size(max = IssueImportRequest.MAX_ITEMS, message = "한 번에 " + IssueImportRequest.MAX_ITEMS + "건까지 가져올 수 있습니다. 나눠서 가져오세요")
        @Valid List<Item> items) {

    public static final int MAX_ITEMS = 5000;

    public record Item(
            @Size(max = 40, message = "키는 40자 이하여야 합니다") String key,
            @Size(max = 300, message = "이슈 제목은 300자 이하여야 합니다") String title,
            @Size(max = 50000, message = "이슈 설명은 50000자 이하여야 합니다") String description,
            String type,
            @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다") String status,
            String priority,
            Long assigneeId,
            @Valid IssueDetailsRequest details) {

        public IssueCreateRequest toCreate() {
            return new IssueCreateRequest(title, description, type, status, priority, assigneeId, details, null);
        }
    }
}
