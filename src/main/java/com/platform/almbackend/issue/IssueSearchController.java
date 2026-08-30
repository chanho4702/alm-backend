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

@RestController
@RequiredArgsConstructor
public class IssueSearchController {
    private final IssueSearchService search;

    /**
     * 서버 검색·페이징. 목록 파라미터는 반복(`statuses=todo&statuses=done`)이나 콤마로 준다.
     * `projectIds`가 없으면 접근 가능한 프로젝트 전체. `sort`: updated(기본)·created·due·priority·key, `dir`: desc(기본)·asc.
     */
    @GetMapping("/api/alm/issues/search")
    public IssuePageResponse search(
            @RequestParam(required = false) List<Long> projectIds,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) List<String> priorities,
            @RequestParam(required = false) List<String> types,
            @RequestParam(required = false) List<String> assignees,
            @RequestParam(required = false) List<String> labels,
            @RequestParam(required = false) List<Long> componentIds,
            @RequestParam(required = false) Long sprintId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Long fixVersionId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return search.search(userId(jwt), new IssueSearchService.Criteria(
                projectIds, text, statuses, priorities, types, assignees, labels, componentIds, sprintId, parentId, fixVersionId, sort, dir), page, size);
    }

    /** 키 단건 조회 — 프로젝트를 순회하지 않는다 */
    @GetMapping("/api/alm/issues/by-key/{key}")
    public IssueResponse byKey(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        return search.byKey(userId(jwt), key);
    }
}
