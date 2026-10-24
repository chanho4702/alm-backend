package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssueBulkUpdateRequest;
import com.platform.almbackend.issue.dto.IssueBulkUpdateResponse;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueImportRequest;
import com.platform.almbackend.issue.dto.IssueImportResponse;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueRankRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueTransitionRequest;
import com.platform.almbackend.issue.dto.IssueTransitionsResponse;
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
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequiredArgsConstructor
@Tag(name = "Issues", description = "이슈 생성·수정·이동·순서 변경·가져오기")
public class IssueController {
    private final IssueService issues;
    private final IssueBulkService bulk;
    private final IssueImportService imports;
    private final IssueRankService ranking;
    private final IssueCloneService cloner;
    private final IssueTransitionService transitions;

    @Operation(summary = "프로젝트의 이슈 목록을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/issues")
    public List<IssueResponse> list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return issues.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트에 이슈를 만든다")
    @PostMapping("/api/alm/projects/{projectId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Valid @RequestBody IssueCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.create(userId(jwt), projectId, request);
    }

    /** 이관·CSV 가져오기 — 200 + 항목별 결과 */
    @Operation(summary = "이슈를 일괄로 가져온다 — 항목별 성공·실패를 함께 돌려준다")
    @PostMapping("/api/alm/projects/{projectId}/issues/import")
    public IssueImportResponse importIssues(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Valid @RequestBody IssueImportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.importIssues(userId(jwt), projectId, request);
    }

    /**
     * 일괄 편집 — 이슈 N개에 같은 필드 변경을 한 요청으로. 행마다 자기 트랜잭션이라 일부만 성공할 수 있고,
     * 항상 200 + 행별 결과다(요청 자체가 잘못되면 400).
     */
    @Operation(summary = "여러 이슈의 필드를 한 번에 바꾼다 — 행별 결과(버전 충돌 포함)를 돌려준다",
            description = "changes에서 키를 보낸 필드만 바뀐다(assigneeId·sprintId·fixVersionId는 null이면 해제). "
                    + "items[].expectedVersion이 서버 버전과 다르면 그 행만 CONFLICT(currentVersion 포함)이고 나머지는 계속 처리한다. "
                    + "행마다 단건 수정과 같은 권한·전이·검증 규칙을 탄다. 최대 200건.")
    @PostMapping("/api/alm/issues/bulk")
    public IssueBulkUpdateResponse bulkUpdate(
            @Valid @RequestBody IssueBulkUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return bulk.update(userId(jwt), request);
    }

    @Operation(summary = "이슈를 복제한다 — 같은 프로젝트에 내용만 복사하고 원본과 \"복제\" 링크로 잇는다",
            description = "복사: 제목(\"복제: \" 접두)·타입·본문·우선순위·라벨·컴포넌트·상위 항목. 복사 안 함: 댓글·워크로그·첨부·"
                    + "담당자·스프린트·마감일·예상 시간·수정 버전, 상태는 워크플로의 처음 상태. 원본 VIEW + 프로젝트 EDIT가 필요하다.")
    @PostMapping("/api/alm/issues/{issueId}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse cloneIssue(@Parameter(description = "원본 이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return cloner.cloneIssue(userId(jwt), issueId);
    }

    @Operation(summary = "이슈 하나를 조회한다")
    @GetMapping("/api/alm/issues/{issueId}")
    public IssueResponse get(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return issues.get(userId(jwt), issueId);
    }

    @Operation(summary = "이슈를 수정한다 — expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/issues/{issueId}")
    public IssueResponse update(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @Valid @RequestBody IssueUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.update(userId(jwt), issueId, request);
    }

    @Operation(summary = "이슈를 다른 보드 컬럼(상태)으로 옮긴다")
    @PostMapping("/api/alm/issues/{issueId}/move")
    public IssueResponse move(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @Valid @RequestBody IssueMoveRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ranking.move(userId(jwt), issueId, request);
    }

    @Operation(summary = "이슈의 가용 전환 목록 — 지금 상태에서 갈 수 있는 전환과 조건 충족 여부",
            description = "조건(담당자만·역할·하위 이슈 완료) 때문에 못 하는 전환도 available=false와 unavailableReason으로 준다. "
                    + "전환 목록이 빈 워크플로는 다른 모든 상태로 가는 암묵 전환(id = implicit:<상태 ID>)을 준다. 프로젝트 VIEW가 필요하다.")
    @GetMapping("/api/alm/issues/{issueId}/transitions")
    public IssueTransitionsResponse transitions(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @AuthenticationPrincipal Jwt jwt) {
        return transitions.available(userId(jwt), issueId);
    }

    @Operation(summary = "전환을 실행한다 — 전환 화면 값(해결·담당자·수정 버전·마감일)과 코멘트를 한 요청으로",
            description = "조건·검증 미충족은 400 {\"error\"}(권한 없음 403과 구분). 화면에 없는 칸을 보내면 400. "
                    + "expectedVersion을 주면 어긋날 때 409. 완료 의미 상태로 가면 해결이 기본 \"완료\"(또는 화면 값·후처리 값)로, "
                    + "완료에서 나가면 비워진다.")
    @PostMapping("/api/alm/issues/{issueId}/transitions")
    public IssueResponse transition(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @Valid @RequestBody IssueTransitionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return transitions.transition(userId(jwt), issueId, request);
    }

    @Operation(summary = "백로그·스프린트 안에서 이슈 순서를 바꾼다")
    @PostMapping("/api/alm/issues/{issueId}/rank")
    public IssueResponse rank(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @Valid @RequestBody(required = false) IssueRankRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ranking.rank(userId(jwt), issueId, request);
    }

    @Operation(summary = "이슈를 삭제한다")
    @DeleteMapping("/api/alm/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        issues.delete(userId(jwt), issueId);
    }

    /** sprint 컨트롤러가 import static으로 공용 — 반드시 public. */
    public static long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("JWT sub는 숫자 사용자 ID여야 합니다", e); }
    }
}

