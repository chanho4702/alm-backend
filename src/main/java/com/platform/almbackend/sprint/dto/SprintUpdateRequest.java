package com.platform.almbackend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 스프린트 계획 메타 수정. 목표·예정 기간은 비울 수 있어서 null을 허용하고, 이름과
 * expectedVersion만 필수다. 기간 역전 검증은 도메인(`Sprint.editPlan`)이 한다.
 */
public record SprintUpdateRequest(
        @NotBlank(message = "스프린트 이름을 입력하세요")
        @Size(max = 120, message = "스프린트 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 255, message = "스프린트 목표는 255자 이하여야 합니다")
        String goal,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
