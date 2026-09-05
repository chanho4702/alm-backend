package com.platform.almbackend.collab;

import com.platform.almbackend.collab.CollaborationService.ActivityResponse;
import com.platform.almbackend.collab.CollaborationService.CommentResponse;
import com.platform.almbackend.collab.CollaborationService.LinkResponse;
import com.platform.almbackend.collab.CollaborationService.LinkView;
import com.platform.almbackend.collab.CollaborationService.WorklogResponse;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
public class CollaborationController {
    private final CollaborationService service;

    public record CommentRequest(String body, List<Long> mentionedUserIds) {}
    public record WorklogRequest(BigDecimal hours, String comment, LocalDate workedOn) {}
    public record LinkRequest(long targetId, String type) {}

    @Tag(name = "Comments")
    @Operation(summary = "이슈의 댓글을 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/comments")
    public List<CommentResponse> comments(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.comments(userId(jwt), issueId);
    }

    @Tag(name = "Comments")
    @Operation(summary = "이슈에 댓글을 단다")
    @PostMapping("/api/alm/issues/{issueId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@PathVariable long issueId, @RequestBody CommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addComment(userId(jwt), issueId, request.body(), request.mentionedUserIds());
    }

    @Tag(name = "Comments")
    @Operation(summary = "댓글 본문과 멘션을 수정한다")
    @PutMapping("/api/alm/comments/{id}")
    public CommentResponse updateComment(@PathVariable long id, @RequestBody CommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.updateComment(userId(jwt), id, request.body(), request.mentionedUserIds());
    }

    @Tag(name = "Comments")
    @Operation(summary = "댓글을 삭제한다")
    @DeleteMapping("/api/alm/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteComment(userId(jwt), id);
    }

    @Tag(name = "Worklogs")
    @Operation(summary = "프로젝트의 작업 시간 기록을 기간으로 집계한다")
    @GetMapping("/api/alm/projects/{projectId}/worklogs")
    public List<CollaborationService.ProjectWorklogRow> projectWorklogs(
            @PathVariable long projectId,
            @Parameter(description = "집계 시작일(포함). 생략하면 처음부터")
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate since,
            @Parameter(description = "집계 종료일(포함). 생략하면 끝까지")
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate until,
            @AuthenticationPrincipal Jwt jwt) {
        return service.projectWorklogs(userId(jwt), projectId, since, until);
    }

    @Tag(name = "Worklogs")
    @Operation(summary = "이슈의 작업 시간 기록을 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/worklogs")
    public List<WorklogResponse> worklogs(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.worklogs(userId(jwt), issueId);
    }

    @Tag(name = "Worklogs")
    @Operation(summary = "이슈에 작업 시간을 기록한다")
    @PostMapping("/api/alm/issues/{issueId}/worklogs")
    @ResponseStatus(HttpStatus.CREATED)
    public WorklogResponse addWorklog(@PathVariable long issueId, @RequestBody WorklogRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addWorklog(userId(jwt), issueId, request.hours(), request.comment(), request.workedOn());
    }

    @Tag(name = "Worklogs")
    @Operation(summary = "작업 시간 기록을 삭제한다")
    @DeleteMapping("/api/alm/worklogs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorklog(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteWorklog(userId(jwt), id);
    }

    @Tag(name = "Issue Links")
    @Operation(summary = "이슈에 걸린 연결을 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/links")
    public List<LinkView> links(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.links(userId(jwt), issueId);
    }

    /** 경로의 이슈가 source — blocks면 "이 이슈가 target을 차단" */
    @Tag(name = "Issue Links")
    @Operation(summary = "이슈를 다른 이슈와 연결한다")
    @PostMapping("/api/alm/issues/{issueId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse addLink(@PathVariable long issueId, @RequestBody LinkRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addLink(userId(jwt), issueId, request.targetId(), request.type());
    }

    @Tag(name = "Issue Links")
    @Operation(summary = "이슈 연결을 끊는다")
    @DeleteMapping("/api/alm/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLink(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.removeLink(userId(jwt), id);
    }

    @Tag(name = "Issue History")
    @Operation(summary = "이슈의 활동 피드를 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/activity")
    public List<ActivityResponse> activity(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.activity(userId(jwt), issueId);
    }
}
