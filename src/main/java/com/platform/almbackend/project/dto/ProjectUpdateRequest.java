package com.platform.almbackend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 세부 필드는 선택 — null이면 그대로 둔다. leadId는 clearLead=true로만 비운다 */
public record ProjectUpdateRequest(
        @NotBlank(message = "프로젝트 이름을 입력하세요")
        @Size(max = 120, message = "프로젝트 이름은 120자 이하여야 합니다")
        String name,
        @Size(max = 5000, message = "프로젝트 설명은 5000자 이하여야 합니다")
        String description,
        @Size(max = 60, message = "범주는 60자 이하여야 합니다")
        String category,
        Long leadId,
        Boolean clearLead,
        String defaultAssignee,
        @Size(max = 40, message = "아이콘 이름은 40자 이하여야 합니다")
        String icon,
        @Size(max = 20, message = "색 이름은 20자 이하여야 합니다")
        String color,
        @Size(max = 500, message = "URL은 500자 이하여야 합니다")
        String url,
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion
) {}
