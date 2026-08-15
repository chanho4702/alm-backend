package com.platform.almbackend.project.dto;

import com.platform.almbackend.domain.Project;

import java.time.Instant;

public record ProjectResponse(
        long id,
        String key,
        String name,
        String description,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getKey(), project.getName(), project.getDescription(),
                project.getVersion(), project.getCreatedAt(), project.getUpdatedAt());
    }
}

