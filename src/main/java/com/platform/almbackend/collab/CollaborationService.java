package com.platform.almbackend.collab;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueComment;
import com.platform.almbackend.domain.Worklog;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueCommentRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.WorklogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 코멘트·워크로그 — 작성자 본인이 고치고, 삭제는 작성자 또는 프로젝트 관리자. 이슈 링크·웹 링크는
 * {@link IssueLinkService}, 활동 기록은 {@link IssueActivityService}(2026-10-18 분할 — 동작 불변).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CollaborationService {

    private final IssueRepository issues;
    private final IssueCommentRepository comments;
    private final WorklogRepository worklogs;
    private final ProjectService projectService;
    private final NotificationService notifications;
    private final IssueActivityService activityLog;

    public record CommentResponse(long id, long issueId, long authorId, String body, Instant createdAt, Instant updatedAt) {
        static CommentResponse from(IssueComment c) {
            return new CommentResponse(c.getId(), c.getIssueId(), c.getAuthorId(), c.getBody(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }
    public record WorklogResponse(long id, long issueId, long authorId, BigDecimal hours, String comment, LocalDate workedOn, Instant createdAt) {
        static WorklogResponse from(Worklog w) {
            return new WorklogResponse(w.getId(), w.getIssueId(), w.getAuthorId(), w.getHours(), w.getComment(), w.getWorkedOn(), w.getCreatedAt());
        }
    }
    // ── 코멘트 ──

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return comments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId).stream().map(CommentResponse::from).toList();
    }

    public CommentResponse addComment(long userId, long issueId, String body, List<Long> mentionedUserIds) {
        Issue issue = require(userId, issueId, AlmAction.VIEW);
        String trimmed = requireText(body, "코멘트 내용을 입력하세요");
        Instant now = now();
        IssueComment comment = comments.save(IssueComment.of(issueId, userId, trimmed, now));
        notifications.notifyCommented(userId, issue, now);
        notifications.notifyMentioned(userId, issue, mentionedUserIds, now);
        return CommentResponse.from(comment);
    }

    public CommentResponse updateComment(long userId, long commentId, String body, List<Long> mentionedUserIds) {
        IssueComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다"));
        if (!comment.getAuthorId().equals(userId)) throw new ForbiddenException("본인 댓글만 수정할 수 있습니다");
        // 작성자라도 지금 프로젝트를 볼 수 있어야 고친다 — 권한을 잃은 뒤의 수정을 막는다(감사 A-17)
        Issue issue = require(userId, comment.getIssueId(), AlmAction.VIEW);
        Instant now = now();
        comment.edit(requireText(body, "코멘트 내용을 입력하세요"), now);
        if (mentionedUserIds != null && !mentionedUserIds.isEmpty()) {
            // 수정으로 새로 멘션된 사람만 — 클라이언트가 이전 본문과 비교해 보낸다
            notifications.notifyMentioned(userId, issue, mentionedUserIds, now);
        }
        return CommentResponse.from(comment);
    }

    public void deleteComment(long userId, long commentId) {
        IssueComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다"));
        requireAuthorOrAdmin(userId, comment.getAuthorId(), comment.getIssueId(), "본인 댓글만 삭제할 수 있습니다");
        comments.delete(comment);
    }

    // ── 워크로그 ──

    @Transactional(readOnly = true)
    public List<WorklogResponse> worklogs(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return worklogs.findByIssueIdOrderByWorkedOnAscIdAsc(issueId).stream().map(WorklogResponse::from).toList();
    }

    public WorklogResponse addWorklog(long userId, long issueId, BigDecimal hours, String comment, LocalDate workedOn) {
        require(userId, issueId, AlmAction.EDIT);
        if (hours == null || hours.signum() <= 0) throw new IllegalArgumentException("시간은 0보다 커야 합니다");
        if (workedOn == null) throw new IllegalArgumentException("작업일을 입력하세요");
        Instant now = now();
        Worklog worklog = worklogs.save(Worklog.of(issueId, userId, hours, comment == null ? "" : comment.trim(), workedOn, now));
        activityLog.record(issueId, userId, "worklog", hours.stripTrailingZeros().toPlainString() + "시간 기록", now);
        return WorklogResponse.from(worklog);
    }

    public void deleteWorklog(long userId, long worklogId) {
        Worklog worklog = worklogs.findById(worklogId)
                .orElseThrow(() -> new NotFoundException("워크로그를 찾을 수 없습니다"));
        requireAuthorOrAdmin(userId, worklog.getAuthorId(), worklog.getIssueId(), "본인 워크로그만 삭제할 수 있습니다");
        worklogs.delete(worklog);
    }

    /** 프로젝트 워크로그(가젯·리포트) — 활성 이슈의 기록만, 이슈 키를 붙여 준다 */
    public record ProjectWorklogRow(long id, long issueId, String issueKey, long authorId, BigDecimal hours, String comment, LocalDate workedOn) {}

    @Transactional(readOnly = true)
    public List<ProjectWorklogRow> projectWorklogs(long userId, long projectId, LocalDate since, LocalDate until) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        List<Issue> projectIssues = issues.findByProjectIdOrderBySortOrderAscKeyAsc(projectId);
        if (projectIssues.isEmpty()) return List.of();
        java.util.Map<Long, String> keys = new java.util.HashMap<>();
        for (Issue issue : projectIssues) keys.put(issue.getId(), issue.getKey());
        LocalDate from = since == null ? LocalDate.of(1970, 1, 1) : since;
        LocalDate to = until == null ? LocalDate.of(9999, 12, 31) : until;
        return worklogs.findByIssueIdInAndWorkedOnBetweenOrderByWorkedOnAscIdAsc(keys.keySet(), from, to).stream()
                .map(w -> new ProjectWorklogRow(w.getId(), w.getIssueId(), keys.get(w.getIssueId()), w.getAuthorId(),
                        w.getHours(), w.getComment(), w.getWorkedOn()))
                .toList();
    }

    // ── 내부 ──

    /**
     * 댓글·워크로그 삭제 — 작성자(지금도 프로젝트를 볼 수 있을 때) 또는 프로젝트 관리자(지라 "모든 댓글 삭제").
     * 휴지통 프로젝트는 누구도 손대지 못한다(require가 404).
     */
    private void requireAuthorOrAdmin(long userId, Long authorId, long issueId, String ownerOnlyMessage) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        if (!Objects.equals(authorId, userId) && !projectService.hasGrant(userId, issue.getProjectId(), AlmAction.ADMIN)) {
            throw new ForbiddenException(ownerOnlyMessage);
        }
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
    }

    private Issue require(long userId, long issueId, AlmAction action) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        projectService.require(userId, issue.getProjectId(), action);
        return issue;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
