package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 이슈의 가용 전환 — 지라 getTransitions(isGlobal·isAvailable·hasScreen)와 같은 뜻으로 맞춘다(2026-10-24 A2) */
@Schema(description = "현재 상태에서 할 수 있는 전환 목록. 조건 때문에 못 하는 전환도 숨기지 않고 available=false와 이유를 준다")
public record IssueTransitionsResponse(
        @Schema(description = "이슈 ID") long issueId,
        @Schema(description = "현재 상태 ID") String status,
        @Schema(description = "현재 이슈 버전 — 전환 실행의 expectedVersion으로 보낸다") int version,
        @Schema(description = "이 이슈에 적용된 워크플로가 이슈 타입별 워크플로인가(false면 프로젝트 기본)") boolean typeWorkflow,
        List<Item> transitions) {

    @Schema(description = "전환 하나")
    public record Item(
            @Schema(description = "전환 ID. 전환 목록이 빈 워크플로(모두 허용)에서는 implicit:<상태 ID>", example = "t-done")
            String id,
            @Schema(description = "전환 이름(비어 있으면 대상 상태 이름)", example = "완료")
            String name,
            @Schema(description = "대상 상태 ID", example = "done") String to,
            @Schema(description = "대상 상태 이름") String toName,
            @Schema(description = "대상 상태 의미 new|active|complete") String toKind,
            @Schema(description = "대상 상태 아이콘(폴백까지 해석된 값)") String toIcon,
            @Schema(description = "모든 상태에서 갈 수 있는 전역 전환인가") boolean isGlobal,
            @Schema(description = "지금 이 사용자가 할 수 있나(조건·편집 권한까지 평가)") boolean available,
            @Schema(description = "못 하는 이유(available=false일 때만)", example = "담당자만 이 전환을 할 수 있습니다")
            String unavailableReason,
            @Schema(description = "전환 화면이 있나(screenFields가 비지 않음)") boolean hasScreen,
            @Schema(description = "전환 화면에서 묻는 칸: resolution·comment·assignee·fixVersion·dueDate 중")
            List<String> screenFields,
            @Schema(description = "전환 검증 — 전환 뒤 값이 있어야 하는 필드 ID") List<String> requiredFields,
            @Schema(description = "requiredFields 중 지금 비어 있는 필드 — 화면 칸으로 채우지 않으면 거부된다")
            List<String> missingFields) {}
}
