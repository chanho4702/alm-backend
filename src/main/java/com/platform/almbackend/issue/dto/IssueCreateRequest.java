package com.platform.almbackend.issue.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이슈 생성 요청. 타입·상태·우선순위를 비우면 프로젝트에 적용된 설정의 기본값을 쓴다.")
public record IssueCreateRequest(
        @Schema(description = "이슈 제목", example = "로그인 후 첫 화면이 비어 있다")
        @NotBlank(message = "이슈 제목을 입력하세요")
        @Size(max = 300, message = "이슈 제목은 300자 이하여야 합니다")
        String title,
        @Schema(description = "이슈 설명(마크다운)", example = "재현: 로그인 → 대시보드 이동 시 목록이 비어 있다.")
        @Size(max = 50000, message = "이슈 설명은 50000자 이하여야 합니다")
        String description,
        @Schema(description = "이슈 타입 ID. 비우면 프로젝트 기본 타입", example = "task")
        String type,
        @Schema(description = "초기 상태 ID. 비우면 워크플로의 첫 상태", example = "todo")
        @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다")
        String status,
        @Schema(description = "우선순위 ID. 비우면 프로젝트 기본 우선순위", example = "medium")
        String priority,
        @Schema(description = "담당자 사용자 ID. 비우면 미지정", example = "42")
        Long assigneeId,
        @Schema(description = "상위 이슈·스프린트·마감일·라벨 등 세부 항목")
        @Valid IssueDetailsRequest details,
        /** 설명에서 @멘션된 사용자 — 알림 대상(선택) */
        @Schema(description = "설명에서 멘션한 사용자 ID. 알림 대상이다", example = "[7, 42]")
        List<Long> mentionedUserIds,
        @Schema(description = "시작일·스토리 포인트(V30). 생략하면 비어 있다")
        @Valid IssuePlanningRequest planning,
        /** 트리아지 대기열로 만든다(V35) — 트리아지를 켠 프로젝트에서만. 편집 권한이 없는 사람이 만든 이슈는 이 값과 무관하게 대기열로 간다 */
        @Schema(description = "true면 트리아지 대기열로 만든다(트리아지를 켠 프로젝트만, 아니면 400). 편집 권한 없이 만든 이슈는 항상 대기열", example = "false")
        Boolean triage
) {
    /** 계획 필드·트리아지 없이 — 기존 호출부(가져오기·복제·테스트)용 */
    public IssueCreateRequest(String title, String description, String type, String status, String priority,
                              Long assigneeId, IssueDetailsRequest details, List<Long> mentionedUserIds) {
        this(title, description, type, status, priority, assigneeId, details, mentionedUserIds, null, null);
    }
}
