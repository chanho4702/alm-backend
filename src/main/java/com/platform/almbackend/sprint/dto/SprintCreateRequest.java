package com.platform.almbackend.sprint.dto;

import jakarta.validation.constraints.Size;

/** 이름을 생략하면 프로젝트 안에서 `Sprint N`으로 자동 명명한다. */
public record SprintCreateRequest(
        @Size(max = 120, message = "스프린트 이름은 120자 이하여야 합니다")
        String name
) {}
