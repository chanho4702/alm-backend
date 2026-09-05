package com.platform.almbackend.project.dto;

import com.platform.almbackend.domain.Project;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "프로젝트 한 건")
public record ProjectResponse(
        @Schema(description = "프로젝트 ID", example = "7")
        long id,
        @Schema(description = "프로젝트 키. 만든 뒤 바뀌지 않는다", example = "ALM")
        String key,
        @Schema(description = "프로젝트 이름", example = "ALM 플랫폼")
        String name,
        @Schema(description = "프로젝트 설명")
        String description,
        @Schema(description = "프로젝트 범주", example = "플랫폼")
        String category,
        @Schema(description = "프로젝트 책임자 사용자 ID", example = "7")
        Long leadId,
        @Schema(description = "새 이슈의 기본 담당자 규칙", example = "PROJECT_LEAD")
        String defaultAssignee,
        @Schema(description = "프로젝트 아이콘 이름", example = "rocket")
        String icon,
        @Schema(description = "프로젝트 색 이름", example = "blue")
        String color,
        @Schema(description = "관련 문서·사이트 URL", example = "https://example.com/alm")
        String url,
        @Schema(description = "낙관적 락 버전. 수정 요청의 expectedVersion에 그대로 넣는다", example = "2")
        int version,
        @Schema(description = "생성 시각", example = "2026-08-01T09:00:00Z")
        Instant createdAt,
        @Schema(description = "마지막 수정 시각", example = "2026-09-04T15:20:00Z")
        Instant updatedAt,
        @Schema(description = "보관 시각. 보관 상태가 아니면 null")
        Instant archivedAt,
        @Schema(description = "휴지통에 들어간 시각. 휴지통이 아니면 null")
        Instant deletedAt,
        /** 휴지통 항목의 영구 삭제 예정 시각(보존 기간 경과 시점). 휴지통이 아니면 null */
        @Schema(description = "영구 삭제 예정 시각(보존 기간이 끝나는 때). 휴지통이 아니면 null")
        Instant purgeAt
) {
    public static ProjectResponse from(Project project, Instant purgeAt) {
        return new ProjectResponse(project.getId(), project.getKey(), project.getName(), project.getDescription(),
                project.getCategory(), project.getLeadId(), project.getDefaultAssignee(), project.getIcon(),
                project.getColor(), project.getUrl(), project.getVersion(), project.getCreatedAt(), project.getUpdatedAt(),
                project.getArchivedAt(), project.getDeletedAt(), purgeAt);
    }
}
