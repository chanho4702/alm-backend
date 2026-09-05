package com.platform.almbackend.issue.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "이슈 전체 수정 요청. details를 생략하면 세부 항목은 그대로 둔다.")
public record IssueUpdateRequest(
        @Schema(description = "이슈 제목", example = "로그인 후 첫 화면이 비어 있다")
        @NotBlank(message = "이슈 제목을 입력하세요")
        @Size(max = 300, message = "이슈 제목은 300자 이하여야 합니다")
        String title,
        @Schema(description = "이슈 설명(마크다운)", example = "재현: 로그인 → 대시보드 이동 시 목록이 비어 있다.")
        @Size(max = 50000, message = "이슈 설명은 50000자 이하여야 합니다")
        String description,
        @Schema(description = "이슈 타입 ID", example = "bug")
        @NotNull(message = "이슈 타입이 필요합니다")
        String type,
        @Schema(description = "상태 ID", example = "in-progress")
        @NotBlank(message = "상태 ID가 필요합니다")
        @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다")
        String status,
        @Schema(description = "우선순위 ID", example = "high")
        @NotNull(message = "우선순위가 필요합니다")
        String priority,
        @Schema(description = "담당자 사용자 ID. null이면 미지정으로 바꾼다", example = "42")
        Long assigneeId,
        @Schema(description = "상위 이슈·스프린트·마감일·라벨 등 세부 항목. 생략하면 기존 값을 보존한다")
        @Valid IssueDetailsRequest details,
        @Schema(description = "수정 직전에 읽은 이슈 버전. 서버 값과 다르면 409", example = "3")
        @NotNull(message = "expectedVersion이 필요합니다")
        Integer expectedVersion,
        /** 설명에서 새로 @멘션된 사용자 — 알림 대상(선택) */
        @Schema(description = "설명에서 새로 멘션한 사용자 ID. 알림 대상이다", example = "[7, 42]")
        List<Long> mentionedUserIds
) {}
