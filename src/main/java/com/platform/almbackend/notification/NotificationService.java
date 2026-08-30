package com.platform.almbackend.notification;

import com.platform.almbackend.common.ForbiddenException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueWatcher;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.notification.dto.NotificationResponse;
import com.platform.almbackend.notification.dto.WatchersResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWatcherRepository;
import com.platform.almbackend.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 워처와 인앱 알림. 알림 대상은 **워처 ∪ 담당자 − 행위자**다 — 본인 행동은 본인에게 알리지 않는다.
 * 보고자는 생성 때, 담당자는 배정 때 자동으로 워처가 된다(지라 기본 동작). 문장은 프론트가 만든다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {
    private static final int PAGE = 100;

    private final IssueWatcherRepository watchers;
    private final NotificationRepository notifications;
    private final IssueRepository issues;
    private final ProjectService projectService;

    // ── 워처 ──

    @Transactional(readOnly = true)
    public WatchersResponse watchers(long userId, long issueId) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return response(userId, issueId);
    }

    public WatchersResponse watch(long userId, long issueId) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        addWatcher(issueId, userId, now());
        return response(userId, issueId);
    }

    public WatchersResponse unwatch(long userId, long issueId) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        watchers.deleteByIssueIdAndUserId(issueId, userId);
        return response(userId, issueId);
    }

    /** 이슈 생성 — 보고자(=생성자)와 담당자가 자동 워처 */
    public void onIssueCreated(long actorId, Issue issue) {
        Instant now = now();
        addWatcher(issue.getId(), actorId, now);
        if (issue.getAssigneeId() != null) {
            addWatcher(issue.getId(), issue.getAssigneeId(), now);
            if (!issue.getAssigneeId().equals(actorId)) {
                notifications.save(Notification.of(
                        issue.getAssigneeId(), issue, actorId, Notification.Type.ASSIGNED, null, now));
            }
        }
    }

    /** 이슈 수정 — 담당자 변경은 새 담당자에게, 상태 변경은 워처에게 */
    public void onIssueUpdated(long actorId, Issue issue, String previousStatus, Long previousAssigneeId) {
        Instant now = now();
        Long assignee = issue.getAssigneeId();
        if (assignee != null && !assignee.equals(previousAssigneeId)) {
            addWatcher(issue.getId(), assignee, now);
            if (!assignee.equals(actorId)) {
                notifications.save(Notification.of(assignee, issue, actorId, Notification.Type.ASSIGNED, null, now));
            }
        }
        if (!Objects.equals(previousStatus, issue.getStatus())) {
            for (long recipient : recipients(issue, actorId)) {
                notifications.save(Notification.of(
                        recipient, issue, actorId, Notification.Type.STATUS_CHANGED, issue.getStatus(), now));
            }
        }
    }

    /** 코멘트 — 워처 ∪ 담당자 − 행위자 */
    public void notifyCommented(long actorId, Issue issue, Instant now) {
        for (long recipient : recipients(issue, actorId)) {
            notifications.save(Notification.of(recipient, issue, actorId, Notification.Type.COMMENTED, null, now));
        }
    }

    // ── 알림 ──

    @Transactional(readOnly = true)
    public List<NotificationResponse> mine(long userId) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, PAGE))
                .stream().map(NotificationResponse::from).toList();
    }

    public void markRead(long userId, long notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("알림을 찾을 수 없습니다: " + notificationId));
        if (!notification.getUserId().equals(userId)) {
            throw new ForbiddenException("본인 알림만 읽음 처리할 수 있습니다");
        }
        notification.markRead();
    }

    public int markAllRead(long userId) {
        return notifications.markAllRead(userId);
    }

    // ── 내부 ──

    private Set<Long> recipients(Issue issue, long actorId) {
        Set<Long> set = new LinkedHashSet<>();
        for (IssueWatcher watcher : watchers.findByIssueIdOrderByCreatedAtAsc(issue.getId())) {
            set.add(watcher.getUserId());
        }
        if (issue.getAssigneeId() != null) set.add(issue.getAssigneeId());
        set.remove(actorId);
        return set;
    }

    private void addWatcher(long issueId, long userId, Instant now) {
        if (!watchers.existsByIssueIdAndUserId(issueId, userId)) {
            watchers.save(IssueWatcher.of(issueId, userId, now));
        }
    }

    private WatchersResponse response(long userId, long issueId) {
        List<WatchersResponse.Watcher> list = watchers.findByIssueIdOrderByCreatedAtAsc(issueId).stream()
                .map(w -> new WatchersResponse.Watcher(w.getUserId(), w.getCreatedAt()))
                .toList();
        boolean watching = list.stream().anyMatch(w -> w.userId() == userId);
        return new WatchersResponse(watching, list);
    }

    private Issue requireIssue(long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
