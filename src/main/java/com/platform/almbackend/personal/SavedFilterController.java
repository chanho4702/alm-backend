package com.platform.almbackend.personal;

import com.platform.almbackend.config.ConflictResponse;
import com.platform.almbackend.config.NoOrgDependency;
import com.platform.almbackend.personal.SavedFilterService.FilterCreateRequest;
import com.platform.almbackend.personal.SavedFilterService.FilterResponse;
import com.platform.almbackend.personal.SavedFilterService.FilterUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * 내 저장 필터 — 사이드바에 꽂아 두는 검색. 전부 소유자 본인 것만 다루므로 org-service를 부르지 않는다
 * (그래서 {@link NoOrgDependency}). 남의 필터를 지목하면 403이 아니라 404다.
 */
@RestController
@RequiredArgsConstructor
@NoOrgDependency
@Tag(name = "Saved Filters", description = "내 저장 필터(스마트 검색·AQL)")
public class SavedFilterController {

    private final SavedFilterService filters;

    @Operation(summary = "내 저장 필터를 조회한다", description = "이름 순. 남의 필터는 들어 있지 않다.")
    @GetMapping("/api/alm/me/filters")
    public List<FilterResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return filters.list(userId(jwt));
    }

    @Operation(summary = "저장 필터를 만든다",
            description = "kind가 `aql`이면 문법을 검사해 틀리면 400 `{error, position, expected}`로 거절한다.")
    @ConflictResponse("같은 이름의 필터가 있습니다")
    @PostMapping("/api/alm/me/filters")
    @ResponseStatus(HttpStatus.CREATED)
    public FilterResponse create(@RequestBody FilterCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return filters.create(userId(jwt), request);
    }

    @Operation(summary = "저장 필터를 수정한다", description = "보낸 항목만 바뀐다. 안 보낸 항목은 그대로다.")
    @ConflictResponse("같은 이름의 필터가 있습니다")
    @PutMapping("/api/alm/me/filters/{id}")
    public FilterResponse update(@Parameter(description = "저장 필터 ID") @PathVariable long id,
                                 @RequestBody FilterUpdateRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return filters.update(userId(jwt), id, request);
    }

    @Operation(summary = "저장 필터를 삭제한다")
    @DeleteMapping("/api/alm/me/filters/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "저장 필터 ID") @PathVariable long id,
                       @AuthenticationPrincipal Jwt jwt) {
        filters.delete(userId(jwt), id);
    }
}
