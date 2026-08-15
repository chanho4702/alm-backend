package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueCreateRequest(
        @NotBlank(message = "이슈 제목을 입력하세요")
        @Size(max = 300, message = "이슈 제목은 300자 이하여야 합니다")
        String title,
        @Size(max = 50000, message = "이슈 설명은 50000자 이하여야 합니다")
        String description,
        IssueType type,
        @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다")
        String status,
        IssuePriority priority,
        Long assigneeId
) {}

