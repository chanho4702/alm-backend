package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * 이슈 카드 — 위키 스마트 링크처럼 다른 제품이 이슈를 한 줄로 그릴 때 쓰는 요약. 표시명·아이콘·색과 담당자
 * 이름까지 채워서 준다(받는 쪽이 레지스트리·org를 따로 묻지 않게). 계약 정본은 W3 스펙 §4.1.
 */
@Schema(description = "이슈 카드 — 스마트 링크용 요약")
public record IssueCardResponse(
        @Schema(description = "이슈 키", example = "PLAT-12")
        String key,
        @Schema(description = "프로젝트 ID", example = "3")
        long projectId,
        @Schema(description = "프로젝트 키", example = "PLAT")
        String projectKey,
        @Schema(description = "이슈 제목")
        String title,
        @Schema(description = "이슈 타입 — 레지스트리 표시값")
        Ref type,
        @Schema(description = "상태 — 프로젝트의 해석된 설정 기준 표시값")
        Status status,
        @Schema(description = "우선순위 — 레지스트리 표시값")
        Ref priority,
        @Schema(description = "담당자. 없으면 null", nullable = true)
        Assignee assignee,
        @Schema(description = "마감일. 없으면 null", example = "2026-11-30", nullable = true)
        LocalDate dueDate,
        @Schema(description = "완료 분류(kind=complete) 상태이거나 해결(resolution)이 있으면 true")
        boolean resolved,
        @Schema(description = "키만으로 여는 ALM 주소", example = "/alm/browse/PLAT-12")
        String url) {

    /** 타입·우선순위 표시값. 레지스트리에 없는 ID면 name=id, icon·color=null */
    @Schema(description = "레지스트리 항목 표시값")
    public record Ref(
            @Schema(description = "ID", example = "task") String id,
            @Schema(description = "표시명", example = "작업") String name,
            @Schema(description = "아이콘 키(없으면 null)", example = "check-square", nullable = true) String icon,
            @Schema(description = "색(Lozenge appearance 이름, 없으면 null)", example = "info", nullable = true) String color) {}

    /** 상태 표시값. kind·color는 상태 분류(카테고리)의 값이다. 풀지 못한 ID면 name=id, 나머지는 null */
    @Schema(description = "상태 표시값")
    public record Status(
            @Schema(description = "상태 ID", example = "inprogress") String id,
            @Schema(description = "표시명", example = "진행 중") String name,
            @Schema(description = "분류 의미 new·active·complete(풀지 못하면 null)", example = "active", nullable = true) String kind,
            @Schema(description = "분류 색 — neutral·info·success·warning·danger(풀지 못하면 null)", example = "info", nullable = true) String color,
            @Schema(description = "아이콘 키(풀지 못하면 null)", example = "circle", nullable = true) String icon) {}

    /** 담당자 — 이름은 org-service에서 읽는다. org 장애·미등록이면 displayName=null */
    @Schema(description = "담당자")
    public record Assignee(
            @Schema(description = "사용자 ID", example = "7") long id,
            @Schema(description = "표시 이름(못 읽으면 null)", example = "Alice Kim", nullable = true) String displayName) {}
}
