package com.platform.almbackend.sprint.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 스프린트 완료 응답 — 완료된 스프린트(기존 {@link SprintResponse} 필드 그대로, 펼쳐서) + 미완료 이슈가 간 곳(V39 사이클).
 */
@Schema(description = "스프린트 완료 결과 — 스프린트 필드 + carryOver")
public record SprintCompleteResponse(
        @JsonUnwrapped SprintResponse sprint,
        CarryOver carryOver) {

    @Schema(description = "미완료 이슈 이관 결과")
    public record CarryOver(
            @Schema(description = "sprint · backlog", example = "sprint")
            String target,
            @Schema(description = "이관된 스프린트 id(backlog면 null)", example = "13")
            Long sprintId,
            @Schema(description = "옮긴 미완료 이슈 수", example = "4")
            int movedCount,
            @Schema(description = "다음 예정 스프린트 id(없으면 null) — 이관 대상과 별개로 알려 준다", example = "13")
            Long nextSprintId,
            @Schema(description = "다음 스프린트를 이번 완료가 자동으로 만들었는가(계획 설정 autoCreateNext)")
            boolean nextSprintAutoCreated) {}
}
