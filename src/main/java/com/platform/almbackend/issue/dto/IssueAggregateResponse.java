package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 이슈 집계 — total은 조건에 맞는 전체 건수, 버킷은 실제로 나온 값만 건수 내림차순·키 오름차순 */
@Schema(description = "조건에 맞는 이슈를 한 축으로 센 결과")
public record IssueAggregateResponse(
        @Schema(description = "조건에 맞는 전체 이슈 수", example = "42")
        long total,
        @Schema(description = "값별 건수. 건수 내림차순, 같으면 키 오름차순")
        List<Bucket> buckets) {

    @Schema(description = "집계 칸 하나")
    public record Bucket(
            @Schema(description = "집계 값 — 상태·우선순위·타입·분류 id 또는 사용자 id. 담당자 미지정은 `unassigned`",
                    example = "inprogress")
            String key,
            @Schema(description = "건수", example = "7")
            long count) {}
}
