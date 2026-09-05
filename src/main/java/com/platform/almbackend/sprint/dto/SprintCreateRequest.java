package com.platform.almbackend.sprint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 이름을 생략하면 프로젝트 안에서 `Sprint N`으로 자동 명명한다. */
@Schema(description = "스프린트 생성 요청. 본문 전체를 생략할 수 있다.")
public record SprintCreateRequest(
        @Schema(description = "스프린트 이름. 비우면 프로젝트 안에서 `Sprint N`으로 자동 명명한다", example = "Sprint 3")
        @Size(max = 120, message = "스프린트 이름은 120자 이하여야 합니다")
        String name
) {}
