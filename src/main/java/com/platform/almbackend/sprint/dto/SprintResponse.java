package com.platform.almbackend.sprint.dto;

import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "스프린트 한 건")
public record SprintResponse(
        @Schema(description = "스프린트 ID", example = "12")
        long id,
        @Schema(description = "스프린트가 속한 프로젝트 ID", example = "7")
        long projectId,
        @Schema(description = "스프린트 이름", example = "Sprint 3")
        String name,
        @Schema(description = "스프린트 상태")
        SprintState state,
        @Schema(description = "스프린트 목표", example = "결제 흐름 안정화")
        String goal,
        @Schema(description = "예정 시작일", example = "2026-09-07")
        LocalDate plannedStart,
        @Schema(description = "예정 종료일", example = "2026-09-20")
        LocalDate plannedEnd,
        @Schema(description = "실제 시작 시각. 시작 전이면 null")
        Instant startedAt,
        @Schema(description = "완료 시각. 완료 전이면 null")
        Instant completedAt,
        @Schema(description = "낙관적 락 버전. 수정 요청의 expectedVersion에 그대로 넣는다", example = "1")
        int version,
        @Schema(description = "생성 시각", example = "2026-09-01T09:00:00Z")
        Instant createdAt,
        @Schema(description = "마지막 수정 시각", example = "2026-09-04T15:20:00Z")
        Instant updatedAt
) {
    public static SprintResponse from(Sprint sprint) {
        return new SprintResponse(sprint.getId(), sprint.getProjectId(), sprint.getName(), sprint.getState(),
                sprint.getGoal(), sprint.getPlannedStart(), sprint.getPlannedEnd(),
                sprint.getStartedAt(), sprint.getCompletedAt(), sprint.getVersion(),
                sprint.getCreatedAt(), sprint.getUpdatedAt());
    }
}
