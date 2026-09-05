package com.platform.almbackend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 세부 필드는 선택 — null이면 그대로 둔다. leadId는 clearLead=true로만 비운다 */
@Schema(description = "프로젝트 수정 요청. 이름과 expectedVersion만 필수이고, 나머지는 null이면 기존 값을 보존한다.")
public record ProjectUpdateRequest(
        @Schema(description = "프로젝트 이름", example = "ALM 플랫폼")
        @NotBlank(message = "프로젝트 이름을 입력하세요")
        @Size(max = 120, message = "프로젝트 이름은 120자 이하여야 합니다")
        String name,
        @Schema(description = "프로젝트 설명")
        @Size(max = 5000, message = "프로젝트 설명은 5000자 이하여야 합니다")
        String description,
        @Schema(description = "프로젝트 범주", example = "플랫폼")
        @Size(max = 60, message = "범주는 60자 이하여야 합니다")
        String category,
        @Schema(description = "프로젝트 책임자 사용자 ID", example = "7")
        Long leadId,
        @Schema(description = "true면 책임자를 비운다. leadId를 null로 보내는 것만으로는 비워지지 않는다", example = "false")
        Boolean clearLead,
        @Schema(description = "새 이슈의 기본 담당자 규칙", example = "PROJECT_LEAD")
        String defaultAssignee,
        @Schema(description = "프로젝트 아이콘 이름", example = "rocket")
        @Size(max = 40, message = "아이콘 이름은 40자 이하여야 합니다")
        String icon,
        @Schema(description = "프로젝트 색 이름", example = "blue")
        @Size(max = 20, message = "색 이름은 20자 이하여야 합니다")
        String color,
        @Schema(description = "관련 문서·사이트 URL", example = "https://example.com/alm")
        @Size(max = 500, message = "URL은 500자 이하여야 합니다")
        String url,
        @Schema(description = "수정 직전에 읽은 프로젝트 버전. 서버 값과 다르면 409", example = "2")
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
