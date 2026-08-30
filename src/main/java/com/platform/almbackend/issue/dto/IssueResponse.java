package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;

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
        String type,
        String status,
        String priority,
        Long assigneeId,
        long reporterId,
        Long parentId,
        Long sprintId,
        LocalDate dueDate,
        BigDecimal estimateHours,
        IssueResolution resolution,
        Long fixVersionId,
        List<String> labels,
        long order,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
    public static IssueResponse from(Issue issue) {
        return new IssueResponse(issue.getId(), issue.getKey(), issue.getProjectId(), issue.getTitle(),
                issue.getDescription(), issue.getType(), issue.getStatus(), issue.getPriority(),
                issue.getAssigneeId(), issue.getReporterId(), issue.getParentId(), issue.getSprintId(),
                issue.getDueDate(),
                issue.getEstimateHours(), issue.getResolution(), issue.getFixVersionId(), List.copyOf(issue.getLabels()), issue.getSortOrder(),
                issue.getVersion(), issue.getCreatedAt(), issue.getUpdatedAt(), issue.getArchivedAt());
    }
}
