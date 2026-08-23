package com.platform.almbackend.issue.dto;

import jakarta.validation.constraints.Positive;

/**
 * 백로그/스프린트 랭크 이동. 대상 그룹은 같은 프로젝트 + `sprintId`(상태 무관)이고,
 * `sprintId`가 없으면 백로그다. `beforeId`가 그룹에 없으면 조용히 맨 뒤에 놓는다 —
 * 드래그 도중 다른 곳에서 옮겨진 stale 참조를 오류로 만들지 않기 위해서다.
 */
public record IssueRankRequest(
        @Positive(message = "sprintId는 양수여야 합니다")
        Long sprintId,
        @Positive(message = "beforeId는 양수여야 합니다")
        Long beforeId
) {}
