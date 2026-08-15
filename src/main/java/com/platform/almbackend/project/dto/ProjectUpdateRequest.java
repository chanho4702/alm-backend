package com.platform.almbackend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProjectUpdateRequest(
        @NotBlank(message = "프로젝트 이름을 입력하세요")
        @Size(max = 120, message = "프로젝트 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 5000, message = "프로젝트 설명은 5000자 이하여야 합니다")
        String description,
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}

