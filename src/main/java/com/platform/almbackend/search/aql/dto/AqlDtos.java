package com.platform.almbackend.search.aql.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platform.almbackend.issue.dto.IssueResponse;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** AQL 엔드포인트의 요청·응답 계약. 프론트 {@code store/aql/} 어댑터가 이 shape에 맞춘다. */
public final class AqlDtos {

    private AqlDtos() {}

    /** 질의 문자열 상한 — 이걸 안 막으면 900KB 질의가 파서까지 들어온다(리뷰 I1) */
    public static final int MAX_AQL = 4000;

    @Schema(description = "AQL 실행 요청")
    public record QueryRequest(
            @Schema(description = "AQL 문자열. 비우면 전체 + 기본 정렬(updated DESC)",
                    example = "project = ALM AND status != 완료 ORDER BY due ASC")
            @Size(max = MAX_AQL, message = "AQL은 4000자 이하여야 합니다")
            String aql,
            @Schema(description = "0부터 세는 페이지 번호", example = "0") Integer page,
            @Schema(description = "한 페이지 항목 수(최대 200)", example = "50") Integer size) {}

    @Schema(description = "AQL 실행 결과 — 기존 검색 응답과 같은 이슈 shape에 echoedAql을 더한다")
    public record QueryResponse(
            @Schema(description = "이슈 목록") List<IssueResponse> items,
            @Schema(description = "0부터 세는 페이지 번호") int page,
            @Schema(description = "한 페이지 항목 수") int size,
            @Schema(description = "조건을 적용한 전체 건수") long total,
            @Schema(description = "서버가 실제로 실행한 AQL — 저장 필터·URL 공유의 기준",
                    example = "project = ALM AND status != 완료 ORDER BY due ASC")
            String echoedAql) {}

    @Schema(description = "AQL 문법 검증 요청")
    public record ValidateRequest(
            @Schema(description = "검사할 AQL 문자열")
            @Size(max = MAX_AQL, message = "AQL은 4000자 이하여야 합니다")
            String aql) {}

    @Schema(description = "AQL 문법 검증 결과. 값 해석(그런 상태·사용자가 있는가)은 하지 않는다")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValidateResponse(
            @Schema(description = "문법·필드·연산자가 모두 맞는가") boolean ok,
            @Schema(description = "틀렸을 때의 한국어 설명") String error,
            @Schema(description = "틀린 자리(0부터 세는 오프셋)") Integer position,
            @Schema(description = "그 자리에 올 수 있었던 것") List<String> expected,
            @Schema(description = "질의가 쓴 필드의 정식명") List<String> fields,
            @Schema(description = "파싱된 AST — 프론트 파서와 shape을 대조할 때 쓴다") Map<String, Object> ast) {}

    @Schema(description = "자동완성용 필드 한 개")
    public record FieldInfo(
            @Schema(description = "정식 필드명", example = "status") String name,
            @Schema(description = "한국어 별칭", example = "[\"상태\"]") List<String> aliases,
            @Schema(description = "값의 성격", example = "ENUM") String kind,
            @Schema(description = "쓸 수 있는 연산자") List<String> operators,
            @Schema(description = "ORDER BY에 쓸 수 있는가") boolean sortable,
            @Schema(description = "IS EMPTY가 의미 있는가") boolean emptyAllowed,
            @Schema(description = "값 후보. 사람은 /api/org/members로 따로 받는다") List<Candidate> values) {}

    @Schema(description = "값 후보 — id로 저장하고 name으로 보여 준다")
    public record Candidate(
            @Schema(description = "질의에 써도 되는 id", example = "inprogress") String id,
            @Schema(description = "사람이 읽는 이름", example = "진행 중") String name) {}

    @Schema(description = "값 자리에 쓸 수 있는 함수")
    public record FunctionInfo(
            @Schema(description = "함수 이름", example = "currentUser") String name,
            @Schema(description = "쓰는 모양", example = "currentUser()") String signature,
            @Schema(description = "이 함수를 받는 필드", example = "[\"assignee\",\"reporter\"]") List<String> fields,
            @Schema(description = "설명") String description) {}

    @Schema(description = "AQL 자동완성 사전")
    public record FieldsResponse(
            @Schema(description = "필드 표") List<FieldInfo> fields,
            @Schema(description = "함수 목록") List<FunctionInfo> functions,
            @Schema(description = "예약어") List<String> keywords) {}
}
