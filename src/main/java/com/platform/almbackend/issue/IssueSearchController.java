package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssueAggregateResponse;
import com.platform.almbackend.issue.dto.IssuePageResponse;
import com.platform.almbackend.issue.dto.IssueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "Issue Search", description = "조건 검색·집계·라벨 후보와 이슈 키 단건 조회")
public class IssueSearchController {
    private final IssueSearchService search;

    /**
     * 서버 검색·페이징. 목록 파라미터는 반복(`statuses=todo&statuses=done`)이나 콤마로 준다.
     * `projectIds`가 없으면 접근 가능한 프로젝트 전체. `sort`: updated(기본)·created·due·priority·key·title·status·type,
     * `dir`: desc(기본)·asc. 모르는 `sort`는 400.
     */
    @Operation(summary = "이슈를 조건으로 검색한다",
            description = "`total`은 조건을 적용한 전체 건수다. 정렬은 기준마다 id 오름차순으로 끝을 묶어 페이지 경계가 흔들리지 않는다. "
                    + "`due` 정렬에서 마감일 없는 이슈는 방향과 무관하게 맨 뒤, `status` 정렬은 분류 순(할 일 → 진행 → 완료) 다음 상태 id 순, "
                    + "`title`은 대소문자를 무시한다. 담당자 정렬은 지원하지 않는다(이름이 org-service에 있다).")
    @GetMapping("/api/alm/issues/search")
    public IssuePageResponse search(
            @Parameter(description = "검색 대상 프로젝트. 비우면 접근 가능한 프로젝트 전체")
            @RequestParam(required = false) List<Long> projectIds,
            @Parameter(description = "제목·설명에서 찾을 문구") @RequestParam(required = false) String text,
            @Parameter(description = "상태 ID 목록") @RequestParam(required = false) List<String> statuses,
            @Parameter(description = "상태 분류 목록(분류 id·kind new/active/complete·이름). 상태 레지스트리로 상태 ID로 풀어 `statuses`와 OR로 합친다. 상태가 없는 분류는 아무것도 잡지 않는다")
            @RequestParam(required = false) List<String> statusCategories,
            @Parameter(description = "true면 완료(kind=complete) 분류의 상태를 뺀다") @RequestParam(defaultValue = "false") boolean openOnly,
            @Parameter(description = "우선순위 ID 목록") @RequestParam(required = false) List<String> priorities,
            @Parameter(description = "이슈 타입 ID 목록") @RequestParam(required = false) List<String> types,
            @Parameter(description = "담당자 사용자 ID 목록. 미지정 이슈는 `unassigned`")
            @RequestParam(required = false) List<String> assignees,
            @Parameter(description = "라벨 목록(하나라도 겹치면)") @RequestParam(required = false) List<String> labels,
            @Parameter(description = "컴포넌트 ID 목록(하나라도 겹치면)") @RequestParam(required = false) List<Long> componentIds,
            @Parameter(description = "이 스프린트의 이슈만") @RequestParam(required = false) Long sprintId,
            @Parameter(description = "이 이슈의 하위 이슈만") @RequestParam(required = false) Long parentId,
            @Parameter(description = "이 릴리스 버전으로 잡힌 이슈만") @RequestParam(required = false) Long fixVersionId,
            @Parameter(description = "마감일이 이 날짜보다 앞선 이슈만(YYYY-MM-DD, 배타). 마감일 없는 이슈는 빠진다", example = "2026-10-16")
            @RequestParam(required = false) String dueBefore,
            @Parameter(description = "마감일이 이 날짜 이후(YYYY-MM-DD, 포함)", example = "2026-10-01")
            @RequestParam(required = false) String dueFrom,
            @Parameter(description = "마감일이 이 날짜 이전(YYYY-MM-DD, 포함)", example = "2026-10-31")
            @RequestParam(required = false) String dueTo,
            @Parameter(description = "이 시각 이후 수정된 이슈만(ISO-8601 시각, 포함)", example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) String updatedSince,
            @Parameter(description = "트리아지 대기열 — 비우면 조건 없음, true면 대기열 이슈만, false면 대기열 밖만(보드·백로그). AQL triage와 같은 판정")
            @RequestParam(required = false) Boolean triage,
            @Parameter(description = "정렬 기준 — updated(기본)·created·due·priority·key·title·status·type")
            @RequestParam(required = false) String sort,
            @Parameter(description = "정렬 방향 — desc(기본)·asc") @RequestParam(required = false) String dir,
            @Parameter(description = "0부터 세는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "한 페이지 항목 수(최대 200)") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return search.search(userId(jwt), new IssueSearchService.Criteria(
                projectIds, text, statuses, statusCategories, openOnly, priorities, types, assignees, labels,
                componentIds, sprintId, parentId, fixVersionId, date("dueBefore", dueBefore), date("dueFrom", dueFrom),
                date("dueTo", dueTo), instant("updatedSince", updatedSince), sort, dir, triage), page, size);
    }

    /**
     * 홈·대시보드 통계용 집계 — 이슈를 내려받아 세지 않고 GROUP BY 한 번으로 센다. 권한 범위·조건은 검색과 같다.
     */
    @Operation(summary = "조건에 맞는 이슈를 한 축으로 센다",
            description = "`groupBy`: status·priority·assignee·type·statusCategory. 담당자 미지정은 키 `unassigned`, "
                    + "statusCategory는 상태 레지스트리의 분류 id로 묶는다(레지스트리에 없는 상태는 `uncategorized`). "
                    + "버킷은 실제로 나온 값만, 건수 내림차순·키 오름차순이다. 볼 수 있는 프로젝트가 없으면 total 0.")
    @GetMapping("/api/alm/issues/aggregate")
    public IssueAggregateResponse aggregate(
            @Parameter(description = "집계 기준 — status·priority·assignee·type·statusCategory", required = true)
            @RequestParam String groupBy,
            @Parameter(description = "대상 프로젝트. 비우면 접근 가능한 프로젝트 전체")
            @RequestParam(required = false) List<Long> projectIds,
            @Parameter(description = "true면 완료(kind=complete) 분류의 상태를 뺀다") @RequestParam(defaultValue = "false") boolean openOnly,
            @Parameter(description = "제목·설명에서 찾을 문구") @RequestParam(required = false) String text,
            @Parameter(description = "상태 ID 목록") @RequestParam(required = false) List<String> statuses,
            @Parameter(description = "상태 분류 목록 — 검색과 같은 규칙") @RequestParam(required = false) List<String> statusCategories,
            @Parameter(description = "우선순위 ID 목록") @RequestParam(required = false) List<String> priorities,
            @Parameter(description = "이슈 타입 ID 목록") @RequestParam(required = false) List<String> types,
            @Parameter(description = "담당자 사용자 ID 목록. 미지정 이슈는 `unassigned`")
            @RequestParam(required = false) List<String> assignees,
            @Parameter(description = "라벨 목록(하나라도 겹치면)") @RequestParam(required = false) List<String> labels,
            @Parameter(description = "컴포넌트 ID 목록(하나라도 겹치면)") @RequestParam(required = false) List<Long> componentIds,
            @Parameter(description = "이 스프린트의 이슈만") @RequestParam(required = false) Long sprintId,
            @Parameter(description = "이 이슈의 하위 이슈만") @RequestParam(required = false) Long parentId,
            @Parameter(description = "이 릴리스 버전으로 잡힌 이슈만") @RequestParam(required = false) Long fixVersionId,
            @Parameter(description = "마감일 < 이 날짜(YYYY-MM-DD, 배타)") @RequestParam(required = false) String dueBefore,
            @Parameter(description = "마감일 ≥ 이 날짜(YYYY-MM-DD, 포함)") @RequestParam(required = false) String dueFrom,
            @Parameter(description = "마감일 ≤ 이 날짜(YYYY-MM-DD, 포함)") @RequestParam(required = false) String dueTo,
            @Parameter(description = "이 시각 이후 수정(ISO-8601 시각, 포함)") @RequestParam(required = false) String updatedSince,
            @Parameter(description = "트리아지 대기열 — 검색과 같은 규칙") @RequestParam(required = false) Boolean triage,
            @AuthenticationPrincipal Jwt jwt) {
        return search.aggregate(userId(jwt), new IssueSearchService.Criteria(
                projectIds, text, statuses, statusCategories, openOnly, priorities, types, assignees, labels,
                componentIds, sprintId, parentId, fixVersionId, date("dueBefore", dueBefore), date("dueFrom", dueFrom),
                date("dueTo", dueTo), instant("updatedSince", updatedSince), null, null, triage), groupBy);
    }

    /** 라벨 선택지 — 목록 화면이 프로젝트 이슈 전량을 읽어 라벨을 모으지 않게 한다 */
    @Operation(summary = "프로젝트에서 쓰이는 라벨을 조회한다",
            description = "보관되지 않은 이슈에 붙은 라벨을 중복 없이, 대소문자 무시 순(같으면 원문 순)으로 준다. 프로젝트 보기 권한이 필요하다.")
    @GetMapping("/api/alm/projects/{projectId}/labels")
    public List<String> labels(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return search.labels(userId(jwt), projectId);
    }

    /** 키 단건 조회 — 프로젝트를 순회하지 않는다 */
    @Operation(summary = "이슈 키로 이슈를 조회한다")
    @GetMapping("/api/alm/issues/by-key/{key}")
    public IssueResponse byKey(
            @Parameter(description = "이슈 키(예: ALM-42)") @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        return search.byKey(userId(jwt), key);
    }

    /** 형식이 틀리면 400 {error} — 스프링 기본 변환 오류 응답은 계약 shape이 아니다 */
    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 날짜 형식이 올바르지 않습니다(YYYY-MM-DD): " + value);
        }
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 시각 형식이 올바르지 않습니다(ISO-8601): " + value);
        }
    }
}
