package com.platform.almbackend.project.dto;

import com.platform.almbackend.domain.Project;

import java.time.Instant;

public record ProjectResponse(
        long id,
        String key,
        String name,
        String description,
        String category,
        Long leadId,
        String defaultAssignee,
        String icon,
        String color,
        String url,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Instant deletedAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getKey(), project.getName(), project.getDescription(),
                project.getCategory(), project.getLeadId(), project.getDefaultAssignee(), project.getIcon(),
                project.getColor(), project.getUrl(), project.getVersion(), project.getCreatedAt(), project.getUpdatedAt(),
                project.getArchivedAt(), project.getDeletedAt());
    }
}
