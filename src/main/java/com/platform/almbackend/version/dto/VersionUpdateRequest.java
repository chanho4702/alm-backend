package com.platform.almbackend.version.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 전체 본문 수정. 날짜는 null로 해제한다. 동시 수정은 expectedVersion으로 막는다. */
public record VersionUpdateRequest(
        @NotBlank(message = "버전 이름을 입력하세요")
        @Size(max = 120, message = "버전 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 2000, message = "버전 설명은 2000자 이하여야 합니다")
        String description,
        LocalDate startDate,
        LocalDate releaseDate,
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
