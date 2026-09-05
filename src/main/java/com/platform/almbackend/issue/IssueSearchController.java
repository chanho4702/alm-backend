package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssuePageResponse;
import com.platform.almbackend.issue.dto.IssueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "Issue Search", description = "조건 검색과 이슈 키 단건 조회")
public class IssueSearchController {
    private final IssueSearchService search;

    /**
     * 서버 검색·페이징. 목록 파라미터는 반복(`statuses=todo&statuses=done`)이나 콤마로 준다.
     * `projectIds`가 없으면 접근 가능한 프로젝트 전체. `sort`: updated(기본)·created·due·priority·key, `dir`: desc(기본)·asc.
     */
    @Operation(summary = "이슈를 조건으로 검색한다")
    @GetMapping("/api/alm/issues/search")
    public IssuePageResponse search(
            @Parameter(description = "검색 대상 프로젝트. 비우면 접근 가능한 프로젝트 전체")
            @RequestParam(required = false) List<Long> projectIds,
            @Parameter(description = "제목·설명에서 찾을 문구") @RequestParam(required = false) String text,
            @Parameter(description = "상태 ID 목록") @RequestParam(required = false) List<String> statuses,
            @Parameter(description = "우선순위 ID 목록") @RequestParam(required = false) List<String> priorities,
            @Parameter(description = "이슈 타입 ID 목록") @RequestParam(required = false) List<String> types,
            @Parameter(description = "담당자 사용자 ID 목록. 미지정 이슈는 `unassigned`")
            @RequestParam(required = false) List<String> assignees,
            @Parameter(description = "라벨 목록") @RequestParam(required = false) List<String> labels,
            @Parameter(description = "컴포넌트 ID 목록") @RequestParam(required = false) List<Long> componentIds,
            @Parameter(description = "이 스프린트의 이슈만") @RequestParam(required = false) Long sprintId,
            @Parameter(description = "이 이슈의 하위 이슈만") @RequestParam(required = false) Long parentId,
            @Parameter(description = "이 릴리스 버전으로 잡힌 이슈만") @RequestParam(required = false) Long fixVersionId,
            @Parameter(description = "정렬 기준 — updated(기본)·created·due·priority·key")
            @RequestParam(required = false) String sort,
            @Parameter(description = "정렬 방향 — desc(기본)·asc") @RequestParam(required = false) String dir,
            @Parameter(description = "0부터 세는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "한 페이지 항목 수") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return search.search(userId(jwt), new IssueSearchService.Criteria(
                projectIds, text, statuses, priorities, types, assignees, labels, componentIds, sprintId, parentId, fixVersionId, sort, dir), page, size);
    }

    /** 키 단건 조회 — 프로젝트를 순회하지 않는다 */
    @Operation(summary = "이슈 키로 이슈를 조회한다")
    @GetMapping("/api/alm/issues/by-key/{key}")
    public IssueResponse byKey(
            @Parameter(description = "이슈 키(예: ALM-42)") @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        return search.byKey(userId(jwt), key);
    }
}
