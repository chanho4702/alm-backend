package com.platform.almbackend.sprint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 스프린트 계획 메타 수정. 목표·예정 기간은 비울 수 있어서 null을 허용하고, 이름과
 * expectedVersion만 필수다. 기간 역전 검증은 도메인(`Sprint.editPlan`)이 한다.
 */
@Schema(description = "스프린트 계획 수정 요청. 목표와 예정 기간은 null로 비울 수 있다.")
public record SprintUpdateRequest(
        @Schema(description = "스프린트 이름", example = "Sprint 3")
        @NotBlank(message = "스프린트 이름을 입력하세요")
        @Size(max = 120, message = "스프린트 이름은 120자 이하여야 합니다")
        String name,
        @Schema(description = "스프린트 목표", example = "결제 흐름 안정화")
        @Size(max = 255, message = "스프린트 목표는 255자 이하여야 합니다")
        String goal,
        @Schema(description = "예정 시작일", example = "2026-09-07")
        LocalDate plannedStart,
        @Schema(description = "예정 종료일. 시작일보다 앞설 수 없다", example = "2026-09-20")
        LocalDate plannedEnd,
        @Schema(description = "수정 직전에 읽은 스프린트 버전. 서버 값과 다르면 409", example = "1")
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
