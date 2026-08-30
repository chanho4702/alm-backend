package com.platform.almbackend.collab;

import com.platform.almbackend.common.ForbiddenException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueActivity;
import com.platform.almbackend.domain.IssueComment;
import com.platform.almbackend.domain.IssueLink;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.domain.Worklog;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueActivityRepository;
import com.platform.almbackend.repository.IssueCommentRepository;
import com.platform.almbackend.repository.IssueLinkRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.WorklogRepository;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.domain.LinkTypeDef;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 코멘트·워크로그·이슈 링크·활동 기록 — 프론트 목업과 같은 규칙(본인 것만 수정·삭제, 링크 중복 금지,
 * 활동은 추가만). 문구의 사용자는 id로 남긴다(디렉터리 연동 전).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CollaborationService {

    private final IssueRepository issues;
    private final IssueCommentRepository comments;
    private final WorklogRepository worklogs;
    private final IssueLinkRepository links;
    private final IssueActivityRepository activities;
    private final ProjectService projectService;
    private final NotificationService notifications;
    private final SchemeService settings;
    private final LinkTypeDefRepository linkTypes;

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
    public record LinkResponse(long id, long sourceId, long targetId, String type) {
        static LinkResponse from(IssueLink l) { return new LinkResponse(l.getId(), l.getSourceId(), l.getTargetId(), l.getType()); }
    }
    /** blocks: outward=차단함, inward=차단됨 / relates: 항상 outward */
    public record LinkView(LinkResponse link, IssueResponse other, String direction) {}
    public record ActivityResponse(long id, long issueId, long actorId, String type, String detail, Instant occurredAt) {
        static ActivityResponse from(IssueActivity a) {
            return new ActivityResponse(a.getId(), a.getIssueId(), a.getActorId(), a.getType(), a.getDetail(), a.getOccurredAt());
        }
    }

    // ── 코멘트 ──

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return comments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId).stream().map(CommentResponse::from).toList();
    }

    public CommentResponse addComment(long userId, long issueId, String body) {
        Issue issue = require(userId, issueId, AlmAction.VIEW);
        String trimmed = requireText(body, "코멘트 내용을 입력하세요");
        Instant now = now();
        IssueComment comment = comments.save(IssueComment.of(issueId, userId, trimmed, now));
        notifications.notifyCommented(userId, issue, now);
        return CommentResponse.from(comment);
    }

    public CommentResponse updateComment(long userId, long commentId, String body) {
        IssueComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다"));
        if (!comment.getAuthorId().equals(userId)) throw new ForbiddenException("본인 댓글만 수정할 수 있습니다");
        comment.edit(requireText(body, "코멘트 내용을 입력하세요"), now());
        return CommentResponse.from(comment);
    }

    public void deleteComment(long userId, long commentId) {
        IssueComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다"));
        if (!comment.getAuthorId().equals(userId)) throw new ForbiddenException("본인 댓글만 삭제할 수 있습니다");
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
        record(issueId, userId, "worklog", hours.stripTrailingZeros().toPlainString() + "시간 기록", now);
        return WorklogResponse.from(worklog);
    }

    public void deleteWorklog(long userId, long worklogId) {
        Worklog worklog = worklogs.findById(worklogId)
                .orElseThrow(() -> new NotFoundException("워크로그를 찾을 수 없습니다"));
        if (!worklog.getAuthorId().equals(userId)) throw new ForbiddenException("본인 워크로그만 삭제할 수 있습니다");
        worklogs.delete(worklog);
    }

    // ── 링크 ──

    @Transactional(readOnly = true)
    public List<LinkView> links(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        List<LinkView> views = new ArrayList<>();
        for (IssueLink link : links.findBySourceIdOrTargetId(issueId, issueId)) {
            long otherId = link.getSourceId() == issueId ? link.getTargetId() : link.getSourceId();
            Issue other = issues.findById(otherId).orElse(null);
            if (other == null) continue;
            boolean symmetric = linkTypes.findById(link.getType()).map(LinkTypeDef::isSymmetric).orElse(false);
            String direction = symmetric || link.getSourceId() == issueId ? "outward" : "inward";
            views.add(new LinkView(LinkResponse.from(link), IssueResponse.from(other), direction));
        }
        return views;
    }

    public LinkResponse addLink(long userId, long sourceId, long targetId, String type) {
        Issue source = require(userId, sourceId, AlmAction.EDIT);
        Issue target = issues.findById(targetId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        if (sourceId == targetId) throw new IllegalArgumentException("자기 자신과는 연결할 수 없습니다");
        LinkTypeDef def = linkTypes.findById(type == null ? "" : type)
                .orElseThrow(() -> new IllegalArgumentException("없는 링크 타입입니다: " + type));
        boolean symmetric = def.isSymmetric();
        boolean duplicate = links.findBySourceIdOrTargetId(sourceId, sourceId).stream().anyMatch(l -> {
            if (!l.getType().equals(type)) return false;
            if (l.getSourceId() == sourceId && l.getTargetId() == targetId) return true;
            return symmetric && l.getSourceId() == targetId && l.getTargetId() == sourceId;
        });
        if (duplicate) throw new IllegalArgumentException("이미 연결돼 있습니다");
        IssueLink link = links.save(IssueLink.of(sourceId, targetId, type));
        Instant now = now();
        String label = def.getName();
        record(source.getId(), userId, "link", label + " 링크: " + target.getKey(), now);
        record(target.getId(), userId, "link", label + " 링크: " + source.getKey(), now);
        return LinkResponse.from(link);
    }

    public void removeLink(long userId, long linkId) {
        IssueLink link = links.findById(linkId).orElseThrow(() -> new NotFoundException("링크를 찾을 수 없습니다"));
        require(userId, link.getSourceId(), AlmAction.EDIT);
        links.delete(link);
    }

    // ── 활동 ──

    @Transactional(readOnly = true)
    public List<ActivityResponse> activity(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return activities.findByIssueIdOrderByOccurredAtAscIdAsc(issueId).stream().map(ActivityResponse::from).toList();
    }

    public void record(long issueId, long actorId, String type, String detail, Instant at) {
        activities.save(IssueActivity.of(issueId, actorId, type, detail, at));
    }

    /** 이슈 생성 활동 */
    public void recordCreated(long actorId, Issue issue) {
        record(issue.getId(), actorId, "created", issue.getKey(), now());
    }

    /**
     * 이슈 수정 전후를 비교해 바뀐 필드마다 한 줄 — 상태·타입은 이름으로, 사용자는 id로 남긴다.
     * 프론트 목업의 recordChanges와 같은 종류(type) 어휘를 쓴다.
     */
    public void recordUpdate(long actorId, Snapshot before, Issue after) {
        Instant now = now();
        long issueId = after.getId();
        if (!Objects.equals(before.status(), after.getStatus())) {
            record(issueId, actorId, "status", statusName(after.getProjectId(), before.status()) + " → " + statusName(after.getProjectId(), after.getStatus()), now);
        }
        if (!Objects.equals(before.assigneeId(), after.getAssigneeId())) {
            record(issueId, actorId, "assignee", userLabel(before.assigneeId()) + " → " + userLabel(after.getAssigneeId()), now);
        }
        if (!Objects.equals(before.priority(), after.getPriority())) {
            record(issueId, actorId, "priority", settings.priorityName(before.priority()) + " → " + settings.priorityName(after.getPriority()), now);
        }
        if (!Objects.equals(before.sprintId(), after.getSprintId())) {
            record(issueId, actorId, "sprint", idLabel(before.sprintId(), "백로그") + " → " + idLabel(after.getSprintId(), "백로그"), now);
        }
        if (!Objects.equals(before.dueDate(), after.getDueDate())) {
            record(issueId, actorId, "duedate", (before.dueDate() == null ? "미지정" : before.dueDate().toString()) + " → " + (after.getDueDate() == null ? "미지정" : after.getDueDate().toString()), now);
        }
        if (!Objects.equals(before.labels(), after.getLabels())) {
            record(issueId, actorId, "labels", after.getLabels().isEmpty() ? "라벨 없음" : String.join(", ", after.getLabels()), now);
        }
        if (!Objects.equals(before.type(), after.getType())) {
            record(issueId, actorId, "issuetype", before.type() + " → " + after.getType(), now);
        }
        if (!Objects.equals(before.parentId(), after.getParentId())) {
            record(issueId, actorId, "parent", idLabel(before.parentId(), "없음") + " → " + idLabel(after.getParentId(), "없음"), now);
        }
        String beforeResolution = before.resolution();
        String afterResolution = after.getResolution() == null ? null : after.getResolution().name();
        if (!Objects.equals(beforeResolution, afterResolution)) {
            record(issueId, actorId, "resolution", (beforeResolution == null ? "미해결" : beforeResolution) + " → " + (afterResolution == null ? "미해결" : afterResolution), now);
        }
        if (!Objects.equals(before.fixVersionId(), after.getFixVersionId())) {
            record(issueId, actorId, "fixversion", idLabel(before.fixVersionId(), "없음") + " → " + idLabel(after.getFixVersionId(), "없음"), now);
        }
    }

    /** 수정 전 값 — 엔티티를 바꾸기 전에 뜬다 */
    public record Snapshot(String status, Long assigneeId, String priority, Long sprintId, LocalDate dueDate,
                           List<String> labels, String type, Long parentId, String resolution, Long fixVersionId) {
        public static Snapshot of(Issue issue) {
            return new Snapshot(issue.getStatus(), issue.getAssigneeId(),
                    issue.getPriority(), issue.getSprintId(),
                    issue.getDueDate(), List.copyOf(issue.getLabels()), issue.getType(), issue.getParentId(),
                    issue.getResolution() == null ? null : issue.getResolution().name(), issue.getFixVersionId());
        }
    }

    // ── 내부 ──

    private Issue require(long userId, long issueId, AlmAction action) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        projectService.require(userId, issue.getProjectId(), action);
        return issue;
    }

    private String statusName(long projectId, String statusId) {
        return settings.body(projectId).statuses().stream()
                .filter(s -> s.id().equals(statusId)).map(s -> s.name()).findFirst().orElse(statusId);
    }

    private static String userLabel(Long userId) {
        return userId == null ? "미지정" : "사용자 " + userId;
    }

    private static String idLabel(Long id, String empty) {
        return id == null ? empty : "#" + id;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
