package com.platform.almbackend.issue;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * 이슈 보관(지라 "보관된 업무 항목") — 보드·목록·검색에서 빠지고 프로젝트 보관함에서 복원한다.
 * 보관은 삭제가 아니라 되돌릴 수 있다. 검색 색인에서는 빠지도록 삭제/생성 이벤트를 낸다.
 */
@RestController
@RequiredArgsConstructor
@Transactional
@Tag(name = "Issue Archive", description = "이슈 보관과 보관함 복원")
public class IssueArchiveController {
    private final IssueRepository issues;
    private final ProjectService projectService;
    private final EventRelay events;

    @Operation(summary = "프로젝트 보관함의 이슈를 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/issues/archived")
    @Transactional(readOnly = true)
    public List<IssueResponse> archived(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        projectService.require(userId(jwt), projectId, AlmAction.VIEW);
        return issues.findArchivedByProject(projectId).stream().map(IssueResponse::from).toList();
    }

    @Operation(summary = "이슈를 보관함으로 옮긴다")
    @PostMapping("/api/alm/issues/{issueId}/archive")
    public IssueResponse archive(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        long actorId = userId(jwt);
        projectService.require(actorId, issue.getProjectId(), AlmAction.EDIT);
        issue.archive(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        events.afterCommit(AlmEvents.issueDeleted(actorId, issue));
        return IssueResponse.from(issue);
    }

    @Operation(summary = "보관된 이슈를 되돌린다")
    @PostMapping("/api/alm/issues/{issueId}/restore")
    public IssueResponse restore(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        Issue issue = issues.findArchivedById(issueId)
                .orElseThrow(() -> new NotFoundException("보관함에 없는 이슈입니다"));
        long actorId = userId(jwt);
        projectService.require(actorId, issue.getProjectId(), AlmAction.EDIT);
        issue.restore();
        events.afterCommit(AlmEvents.issueCreated(actorId, issue));
        return IssueResponse.from(issue);
    }
}
