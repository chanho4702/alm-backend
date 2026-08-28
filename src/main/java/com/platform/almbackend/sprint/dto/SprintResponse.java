package com.platform.almbackend.sprint.dto;

import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;

import java.time.Instant;
import java.time.LocalDate;

public record SprintResponse(
        long id,
        long projectId,
        String name,
        SprintState state,
        String goal,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        Instant startedAt,
        Instant completedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static SprintResponse from(Sprint sprint) {
        return new SprintResponse(sprint.getId(), sprint.getProjectId(), sprint.getName(), sprint.getState(),
                sprint.getGoal(), sprint.getPlannedStart(), sprint.getPlannedEnd(),
                sprint.getStartedAt(), sprint.getCompletedAt(), sprint.getVersion(),
                sprint.getCreatedAt(), sprint.getUpdatedAt());
    }
}
