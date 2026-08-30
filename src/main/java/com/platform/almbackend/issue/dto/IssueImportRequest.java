package com.platform.almbackend.issue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 이관·CSV 가져오기 — 항목마다 키를 보존할 수 있다(`{프로젝트키}-{번호}`, 유일). */
public record IssueImportRequest(@NotEmpty(message = "가져올 이슈가 없습니다") @Valid List<Item> items) {

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
