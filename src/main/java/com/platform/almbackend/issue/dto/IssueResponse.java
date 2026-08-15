package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;

import java.time.Instant;

public record IssueResponse(
        long id,
        String key,
        long projectId,
        String title,
        String description,
        IssueType type,
        String status,
        IssuePriority priority,
        Long assigneeId,
        long reporterId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static IssueResponse from(Issue issue) {
        return new IssueResponse(issue.getId(), issue.getKey(), issue.getProjectId(), issue.getTitle(),
                issue.getDescription(), issue.getType(), issue.getStatus(), issue.getPriority(),
                issue.getAssigneeId(), issue.getReporterId(), issue.getVersion(), issue.getCreatedAt(),
                issue.getUpdatedAt());
    }
}

