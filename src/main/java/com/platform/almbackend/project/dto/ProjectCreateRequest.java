package com.platform.almbackend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "프로젝트 생성 요청")
public record ProjectCreateRequest(
        @Schema(description = "프로젝트 키. 이슈 키의 앞머리가 되며 만든 뒤 바꿀 수 없다", example = "ALM")
        @NotBlank(message = "프로젝트 키를 입력하세요")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{1,11}", message = "프로젝트 키는 영문으로 시작하는 2~12자여야 합니다")
        String key,
        @Schema(description = "프로젝트 이름", example = "ALM 플랫폼")
        @NotBlank(message = "프로젝트 이름을 입력하세요")
        @Size(max = 120, message = "프로젝트 이름은 120자 이하여야 합니다")
        String name,
        @Schema(description = "프로젝트 설명", example = "이슈·스프린트를 관리하는 내부 프로젝트")
        @Size(max = 5000, message = "프로젝트 설명은 5000자 이하여야 합니다")
        String description
) {}
