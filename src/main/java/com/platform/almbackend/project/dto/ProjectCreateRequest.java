package com.platform.almbackend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(
        @NotBlank(message = "프로젝트 키를 입력하세요")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{1,11}", message = "프로젝트 키는 영문으로 시작하는 2~12자여야 합니다")
        String key,
        @NotBlank(message = "프로젝트 이름을 입력하세요")
        @Size(max = 120, message = "프로젝트 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 5000, message = "프로젝트 설명은 5000자 이하여야 합니다")
        String description
) {}

