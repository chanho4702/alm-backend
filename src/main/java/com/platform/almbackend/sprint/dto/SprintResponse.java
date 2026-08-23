package com.platform.almbackend.sprint.dto;

import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;

import java.time.Instant;

public record SprintResponse(
        long id,
        long projectId,
        String name,
        SprintState state,
        Instant startedAt,
        Instant completedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static SprintResponse from(Sprint sprint) {
        return new SprintResponse(sprint.getId(), sprint.getProjectId(), sprint.getName(), sprint.getState(),
                sprint.getStartedAt(), sprint.getCompletedAt(), sprint.getVersion(),
                sprint.getCreatedAt(), sprint.getUpdatedAt());
    }
}
