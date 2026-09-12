package com.platform.almbackend.issue.dto;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "이슈 한 건")
public record IssueResponse(
        @Schema(description = "이슈 ID", example = "1024")
        long id,
        @Schema(description = "사람이 읽는 이슈 키. 만든 뒤 바뀌지 않는다", example = "ALM-42")
        String key,
        @Schema(description = "이슈가 속한 프로젝트 ID", example = "7")
        long projectId,
        @Schema(description = "이슈 제목", example = "로그인 후 첫 화면이 비어 있다")
        String title,
        @Schema(description = "이슈 설명(마크다운)")
        String description,
        @Schema(description = "이슈 타입 ID", example = "bug")
        String type,
        @Schema(description = "상태 ID", example = "in-progress")
        String status,
        @Schema(description = "우선순위 ID", example = "high")
        String priority,
        @Schema(description = "담당자 사용자 ID. 미지정이면 null", example = "42")
        Long assigneeId,
        @Schema(description = "보고자 사용자 ID", example = "7")
        long reporterId,
        @Schema(description = "상위 이슈 ID", example = "1000")
        Long parentId,
        @Schema(description = "속한 스프린트 ID. 백로그면 null", example = "12")
        Long sprintId,
        @Schema(description = "마감일", example = "2026-09-30")
        LocalDate dueDate,
        @Schema(description = "예상 소요 시간(시간 단위)", example = "3.50")
        BigDecimal estimateHours,
        @Schema(description = "완료 사유. 완료되지 않았으면 null")
        IssueResolution resolution,
        @Schema(description = "수정 버전 ID", example = "5")
        Long fixVersionId,
        @Schema(description = "라벨", example = "[\"regression\", \"frontend\"]")
        List<String> labels,
        @Schema(description = "지정된 컴포넌트 ID", example = "[3]")
        List<Long> componentIds,
        @Schema(description = "보드·백로그 안에서의 정렬 순서", example = "3")
        long order,
        @Schema(description = "낙관적 락 버전. 수정 요청의 expectedVersion에 그대로 넣는다", example = "3")
        int version,
        @Schema(description = "생성 시각", example = "2026-09-01T09:00:00Z")
        Instant createdAt,
        @Schema(description = "마지막 수정 시각", example = "2026-09-04T15:20:00Z")
        Instant updatedAt,
        @Schema(description = "보관 시각. 보관 상태가 아니면 null")
        Instant archivedAt,
        @Schema(description = "해결일 — 완료 사유가 처음 붙은 시각. 미해결이면 null",
                example = "2026-09-10T04:12:00Z")
        Instant resolvedAt
) {
    public static IssueResponse from(Issue issue) {
        return new IssueResponse(issue.getId(), issue.getKey(), issue.getProjectId(), issue.getTitle(),
                issue.getDescription(), issue.getType(), issue.getStatus(), issue.getPriority(),
                issue.getAssigneeId(), issue.getReporterId(), issue.getParentId(), issue.getSprintId(),
                issue.getDueDate(),
                issue.getEstimateHours(), issue.getResolution(), issue.getFixVersionId(), List.copyOf(issue.getLabels()), List.copyOf(issue.getComponentIds()), issue.getSortOrder(),
                issue.getVersion(), issue.getCreatedAt(), issue.getUpdatedAt(), issue.getArchivedAt(),
                issue.getResolvedAt());
    }
}
