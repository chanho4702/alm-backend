package com.platform.almbackend.version.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record VersionCreateRequest(
        @NotBlank(message = "버전 이름을 입력하세요")
        @Size(max = 120, message = "버전 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 2000, message = "버전 설명은 2000자 이하여야 합니다")
        String description,
        LocalDate startDate,
        LocalDate releaseDate
) {}
