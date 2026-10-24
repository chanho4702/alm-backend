package com.platform.almbackend.notification;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueWatcher;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.notification.dto.NotificationResponse;
import com.platform.almbackend.notification.dto.WatchersResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.personal.PreferenceService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWatcherRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.common.error.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 워처와 인앱 알림. 알림 대상은 **워처 ∪ 담당자 − 행위자**다 — 본인 행동은 본인에게 알리지 않는다.
 * 보고자는 생성 때, 담당자는 배정 때 자동으로 워처가 된다(지라 기본 동작). 문장은 프론트가 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {
    private static final int PAGE = 100;

    private final IssueWatcherRepository watchers;
    private final NotificationRepository notifications;
    private final IssueRepository issues;
    private final ProjectService projectService;
    private final PreferenceService preferences;
    private final EmailNotifier email;

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
        if (preferences.get(actorId).autoWatch().createdOn()) addWatcher(issue.getId(), actorId, now);
        if (issue.getAssigneeId() != null) {
            addWatcher(issue.getId(), issue.getAssigneeId(), now);
            if (!issue.getAssigneeId().equals(actorId) && preferences.get(issue.getAssigneeId()).notifications().assignedOn()
                    && mayReceive(issue.getAssigneeId(), issue)) {
                email.notify(notifications.save(Notification.of(
                        issue.getAssigneeId(), issue, actorId, Notification.Type.ASSIGNED, null, now)), issue);
            }
        }
    }

    /** 이슈 수정 — 담당자 변경은 새 담당자에게, 상태 변경은 워처에게 */
    public void onIssueUpdated(long actorId, Issue issue, String previousStatus, Long previousAssigneeId) {
        Instant now = now();
        if (preferences.get(actorId).autoWatch().editedOn()) addWatcher(issue.getId(), actorId, now);
        Long assignee = issue.getAssigneeId();
        if (assignee != null && !assignee.equals(previousAssigneeId)) {
            addWatcher(issue.getId(), assignee, now);
            if (!assignee.equals(actorId) && preferences.get(assignee).notifications().assignedOn()
                    && mayReceive(assignee, issue)) {
                email.notify(notifications.save(
                        Notification.of(assignee, issue, actorId, Notification.Type.ASSIGNED, null, now)), issue);
            }
        }
        if (!Objects.equals(previousStatus, issue.getStatus())) {
            for (long recipient : recipients(issue, actorId)) {
                if (!preferences.get(recipient).notifications().statusChangedOn()) continue;
                email.notify(notifications.save(Notification.of(
                                recipient, issue, actorId, Notification.Type.STATUS_CHANGED, issue.getStatus(), now)),
                        issue, previousStatus);
            }
        }
    }

    /** 코멘트 — 워처 ∪ 담당자 − 행위자 */
    public void notifyCommented(long actorId, Issue issue, Instant now) {
        if (preferences.get(actorId).autoWatch().commentedOn()) addWatcher(issue.getId(), actorId, now);
        for (long recipient : recipients(issue, actorId)) {
            if (!preferences.get(recipient).notifications().commentedOn()) continue;
            email.notify(notifications.save(
                    Notification.of(recipient, issue, actorId, Notification.Type.COMMENTED, null, now)), issue);
        }
    }

    /**
     * 본문에서 @멘션된 사용자에게 — 워처 여부와 무관하게, 본인은 제외, 개인 설정(mentioned)을 존중한다.
     * 멘션 목록은 클라이언트가 보내므로 그대로 믿지 않는다: 이슈 프로젝트를 볼 수 없는 사람에게는 알림도
     * 메일(키+제목)도 보내지 않는다(감사 A-03).
     */
    public void notifyMentioned(long actorId, Issue issue, Collection<Long> userIds, Instant now) {
        if (userIds == null || userIds.isEmpty()) return;
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (userId == null || userId == actorId) continue;
            if (!preferences.get(userId).notifications().mentionedOn()) continue;
            if (!mayReceive(userId, issue)) continue;
            email.notify(notifications.save(
                    Notification.of(userId, issue, actorId, Notification.Type.MENTIONED, null, now)), issue);
        }
    }

    /**
     * 자동화 "알림" 액션 — 고른 대상(담당자·보고자·워처·지정 사용자)에게 인앱 알림. 메일은 보내지 않는다.
     * 지금 프로젝트를 볼 수 없는 사람은 뺀다(키·제목이 실리므로, 멘션과 같은 규칙).
     */
    public int notifyAutomation(long actorId, Issue issue, boolean assignee, boolean reporter, boolean watcherSet,
                                Collection<Long> userIds, String message) {
        Set<Long> targets = new LinkedHashSet<>();
        if (assignee && issue.getAssigneeId() != null) targets.add(issue.getAssigneeId());
        if (reporter) targets.add(issue.getReporterId());
        if (watcherSet) {
            for (IssueWatcher watcher : watchers.findByIssueIdOrderByCreatedAtAsc(issue.getId())) targets.add(watcher.getUserId());
        }
        if (userIds != null) userIds.stream().filter(Objects::nonNull).forEach(targets::add);
        Instant now = now();
        int sent = 0;
        for (long recipient : targets) {
            if (!mayReceive(recipient, issue)) continue;
            notifications.save(Notification.of(recipient, issue, actorId, Notification.Type.AUTOMATION, message, now));
            sent++;
        }
        return sent;
    }

    // ── 알림 ──

    /** 알림함을 열 때 메일 주소 스냅샷도 갱신한다 — 발송 시점에는 수신자의 토큰이 없다 */
    public List<NotificationResponse> mine(long userId, String jwtEmail) {
        preferences.rememberEmail(userId, jwtEmail);
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

    /** 워처 ∪ 담당자 − 행위자 − 지금 프로젝트를 볼 수 없는 사람(권한을 잃은 워처) */
    private Set<Long> recipients(Issue issue, long actorId) {
        Set<Long> set = new LinkedHashSet<>();
        for (IssueWatcher watcher : watchers.findByIssueIdOrderByCreatedAtAsc(issue.getId())) {
            set.add(watcher.getUserId());
        }
        if (issue.getAssigneeId() != null) set.add(issue.getAssigneeId());
        set.remove(actorId);
        set.removeIf(recipient -> !mayReceive(recipient, issue));
        return set;
    }

    /**
     * 수신자가 이 이슈를 볼 수 있는가 — 알림 문장과 메일에 키·제목이 실린다. 권한 판정은 30초 캐시라 비용이 작다.
     * org 불능이면 알림만 생략한다: 알림 때문에 본 작업(댓글·수정 저장)을 503으로 실패시키지 않고,
     * 판정할 수 없는 사람에게 내용을 보내지도 않는다(fail-closed).
     */
    private boolean mayReceive(long recipient, Issue issue) {
        try {
            return projectService.canView(recipient, issue.getProjectId());
        } catch (ServiceUnavailableException e) {
            log.warn("권한 서비스 불능으로 알림을 생략합니다 (user {}, issue {})", recipient, issue.getId());
            return false;
        }
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
