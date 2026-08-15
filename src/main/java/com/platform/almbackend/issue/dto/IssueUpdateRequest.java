package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IssueUpdateRequest(
        @NotBlank(message = "이슈 제목을 입력하세요")
        @Size(max = 300, message = "이슈 제목은 300자 이하여야 합니다")
        String title,
        @Size(max = 50000, message = "이슈 설명은 50000자 이하여야 합니다")
        String description,
        @NotNull(message = "이슈 타입이 필요합니다")
        IssueType type,
        @NotBlank(message = "상태 ID가 필요합니다")
        @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다")
        String status,
        @NotNull(message = "우선순위가 필요합니다")
        IssuePriority priority,
        Long assigneeId,
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
