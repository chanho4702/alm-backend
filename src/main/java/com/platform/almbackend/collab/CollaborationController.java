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

@RestController
@RequiredArgsConstructor
public class CollaborationController {
    private final CollaborationService service;

    public record CommentRequest(String body, List<Long> mentionedUserIds) {}
    public record WorklogRequest(BigDecimal hours, String comment, LocalDate workedOn) {}
    public record LinkRequest(long targetId, String type) {}

    @GetMapping("/api/alm/issues/{issueId}/comments")
    public List<CommentResponse> comments(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.comments(userId(jwt), issueId);
    }

    @PostMapping("/api/alm/issues/{issueId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@PathVariable long issueId, @RequestBody CommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addComment(userId(jwt), issueId, request.body(), request.mentionedUserIds());
    }

    @PutMapping("/api/alm/comments/{id}")
    public CommentResponse updateComment(@PathVariable long id, @RequestBody CommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.updateComment(userId(jwt), id, request.body(), request.mentionedUserIds());
    }

    @DeleteMapping("/api/alm/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteComment(userId(jwt), id);
    }

    @GetMapping("/api/alm/projects/{projectId}/worklogs")
    public List<CollaborationService.ProjectWorklogRow> projectWorklogs(
            @PathVariable long projectId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate since,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate until,
            @AuthenticationPrincipal Jwt jwt) {
        return service.projectWorklogs(userId(jwt), projectId, since, until);
    }

    @GetMapping("/api/alm/issues/{issueId}/worklogs")
    public List<WorklogResponse> worklogs(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.worklogs(userId(jwt), issueId);
    }

    @PostMapping("/api/alm/issues/{issueId}/worklogs")
    @ResponseStatus(HttpStatus.CREATED)
    public WorklogResponse addWorklog(@PathVariable long issueId, @RequestBody WorklogRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addWorklog(userId(jwt), issueId, request.hours(), request.comment(), request.workedOn());
    }

    @DeleteMapping("/api/alm/worklogs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorklog(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteWorklog(userId(jwt), id);
    }

    @GetMapping("/api/alm/issues/{issueId}/links")
    public List<LinkView> links(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.links(userId(jwt), issueId);
    }

    /** 경로의 이슈가 source — blocks면 "이 이슈가 target을 차단" */
    @PostMapping("/api/alm/issues/{issueId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse addLink(@PathVariable long issueId, @RequestBody LinkRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.addLink(userId(jwt), issueId, request.targetId(), request.type());
    }

    @DeleteMapping("/api/alm/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLink(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.removeLink(userId(jwt), id);
    }

    @GetMapping("/api/alm/issues/{issueId}/activity")
    public List<ActivityResponse> activity(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.activity(userId(jwt), issueId);
    }
}
