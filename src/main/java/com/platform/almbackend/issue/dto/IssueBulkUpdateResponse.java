package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 일괄 편집 결과 — 요청 items 순서 그대로 행마다 하나 */
@Schema(description = "이슈 일괄 편집 결과")
public record IssueBulkUpdateResponse(
        @Schema(description = "바뀐 이슈 수") int updated,
        @Schema(description = "실패한 이슈 수") int failed,
        @Schema(description = "행별 결과(요청 순서)") List<Row> results) {

    /** 행 결과 코드 */
    public enum Code { OK, CONFLICT, FORBIDDEN, NOT_FOUND, INVALID, UNAVAILABLE, ERROR }

    @Schema(description = "한 이슈의 결과")
    public record Row(
            @Schema(description = "이슈 ID") long issueId,
            @Schema(description = "이슈 키. 볼 수 없거나 없는 이슈면 null", example = "ALM-42") String key,
            @Schema(description = "성공 여부") boolean ok,
            @Schema(description = "OK·CONFLICT(버전 충돌)·FORBIDDEN·NOT_FOUND·INVALID(규칙 위반)·UNAVAILABLE(권한 서비스 불능)·ERROR")
            Code code,
            @Schema(description = "실패 사유(한국어, 그대로 보여 줄 문장). 성공이면 null") String error,
            @Schema(description = "CONFLICT일 때 서버의 현재 버전 — 새로 읽지 않고도 재시도 판단을 돕는다") Integer currentVersion,
            @Schema(description = "성공하면 저장 후 이슈") IssueResponse issue) {}
}
