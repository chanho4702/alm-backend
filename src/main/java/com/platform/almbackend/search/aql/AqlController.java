package com.platform.almbackend.search.aql;

import com.platform.almbackend.search.aql.dto.AqlDtos.FieldsResponse;
import com.platform.almbackend.search.aql.dto.AqlDtos.QueryRequest;
import com.platform.almbackend.search.aql.dto.AqlDtos.QueryResponse;
import com.platform.almbackend.search.aql.dto.AqlDtos.ValidateRequest;
import com.platform.almbackend.search.aql.dto.AqlDtos.ValidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * AQL(ALM Query Language) — JQL처럼 조건을 조합해 이슈를 찾는다. 문법·필드는 {@code README.md}의 AQL 절에 있다.
 *
 * <p>문법·해석 오류는 400 {@code {"error", "position", "expected"}}다 — 프론트 에디터가 {@code position}으로
 * 밑줄을 긋는다({@link AqlExceptionHandler}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Issue AQL", description = "AQL로 이슈를 검색한다")
public class AqlController {

    private final AqlSearchService search;

    @Operation(summary = "AQL로 이슈를 검색한다",
            description = "빈 문자열은 전체 + 기본 정렬(updated DESC). 보관된 이슈는 `archived = true`를 쓸 때만 나온다.")
    @PostMapping("/api/alm/issues/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return search.query(userId(jwt), request.aql(), request.page(), request.size());
    }

    @Operation(summary = "AQL 문법을 검사한다",
            description = "문법·필드·연산자만 본다. 상태·사용자 이름이 실제로 있는지는 실행할 때 확인한다.")
    @PostMapping("/api/alm/issues/query/validate")
    public ValidateResponse validate(@Valid @RequestBody ValidateRequest request) {
        return search.validate(request.aql());
    }

    @Operation(summary = "AQL 자동완성 사전을 읽는다",
            description = "필드·별칭·연산자와 값 후보(상태·타입·우선순위·프로젝트). 사람은 /api/org/members로 따로 받는다.")
    @GetMapping("/api/alm/issues/query/fields")
    public FieldsResponse fields(@AuthenticationPrincipal Jwt jwt) {
        return search.fields(userId(jwt));
    }
}
