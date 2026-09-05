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
        List<Long> mentionedUserIds
) {}
