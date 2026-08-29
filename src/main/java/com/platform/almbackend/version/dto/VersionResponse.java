package com.platform.almbackend.version.dto;

import com.platform.almbackend.domain.ProjectVersion;
import com.platform.almbackend.domain.VersionStatus;

import java.time.Instant;
import java.time.LocalDate;

public record VersionResponse(
        long id,
        long projectId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate releaseDate,
        VersionStatus status,
        Instant releasedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public static VersionResponse from(ProjectVersion v) {
        return new VersionResponse(v.getId(), v.getProjectId(), v.getName(), v.getDescription(),
                v.getStartDate(), v.getReleaseDate(), v.getStatus(), v.getReleasedAt(), v.getVersion(),
                v.getCreatedAt(), v.getUpdatedAt());
    }
}
