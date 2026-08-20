package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
        Long parentId,
        LocalDate dueDate,
        BigDecimal estimateHours,
        List<String> labels,
        long order,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static IssueResponse from(Issue issue) {
        return new IssueResponse(issue.getId(), issue.getKey(), issue.getProjectId(), issue.getTitle(),
                issue.getDescription(), issue.getType(), issue.getStatus(), issue.getPriority(),
                issue.getAssigneeId(), issue.getReporterId(), issue.getParentId(), issue.getDueDate(),
                issue.getEstimateHours(), List.copyOf(issue.getLabels()), issue.getSortOrder(),
                issue.getVersion(), issue.getCreatedAt(), issue.getUpdatedAt());
    }
}
