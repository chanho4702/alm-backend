package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueImportRequest;
import com.platform.almbackend.issue.dto.IssueImportResponse;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueRankRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "Issues", description = "이슈 생성·수정·이동·순서 변경·가져오기")
public class IssueController {
    private final IssueService issues;

    @Operation(summary = "프로젝트의 이슈 목록을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/issues")
    public List<IssueResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return issues.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트에 이슈를 만든다")
    @PostMapping("/api/alm/projects/{projectId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(
            @PathVariable long projectId,
            @Valid @RequestBody IssueCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.create(userId(jwt), projectId, request);
    }

    /** 이관·CSV 가져오기 — 200 + 항목별 결과 */
    @Operation(summary = "이슈를 일괄로 가져온다 — 항목별 성공·실패를 함께 돌려준다")
    @PostMapping("/api/alm/projects/{projectId}/issues/import")
    public IssueImportResponse importIssues(
            @PathVariable long projectId,
            @Valid @RequestBody IssueImportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.importIssues(userId(jwt), projectId, request);
    }

    @Operation(summary = "이슈 하나를 조회한다")
    @GetMapping("/api/alm/issues/{issueId}")
    public IssueResponse get(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return issues.get(userId(jwt), issueId);
    }

    @Operation(summary = "이슈를 수정한다 — expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/issues/{issueId}")
    public IssueResponse update(
            @PathVariable long issueId,
            @Valid @RequestBody IssueUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.update(userId(jwt), issueId, request);
    }

    @Operation(summary = "이슈를 다른 보드 컬럼(상태)으로 옮긴다")
    @PostMapping("/api/alm/issues/{issueId}/move")
    public IssueResponse move(
            @PathVariable long issueId,
            @Valid @RequestBody IssueMoveRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.move(userId(jwt), issueId, request);
    }

    @Operation(summary = "백로그·스프린트 안에서 이슈 순서를 바꾼다")
    @PostMapping("/api/alm/issues/{issueId}/rank")
    public IssueResponse rank(
            @PathVariable long issueId,
            @Valid @RequestBody(required = false) IssueRankRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.rank(userId(jwt), issueId, request);
    }

    @Operation(summary = "이슈를 삭제한다")
    @DeleteMapping("/api/alm/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        issues.delete(userId(jwt), issueId);
    }

    /** sprint 컨트롤러가 import static으로 공용 — 반드시 public. */
    public static long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("JWT sub는 숫자 사용자 ID여야 합니다", e); }
    }
}

