package com.platform.almbackend.notification;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueWatcher;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.notification.dto.NotificationBulkRequest;
import com.platform.almbackend.notification.dto.NotificationBulkResponse;
import com.platform.almbackend.notification.dto.NotificationInboxResponse;
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
import org.springframework.data.domain.Limit;
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

    /**
     * 알림함을 열 때 메일 주소 스냅샷도 갱신한다 — 발송 시점에는 수신자의 토큰이 없다.
     * 인박스(V40)의 첫 쪽과 같은 규칙이다: 보관한 것·지금 미뤄 둔 것은 빠지고, 미루기에서 돌아온 것은 해제 시각 자리에 선다.
     */
    public List<NotificationResponse> mine(long userId, String jwtEmail) {
        preferences.rememberEmail(userId, jwtEmail);
        return notifications.inbox(userId, now(), ALL_TYPES, false, CURSOR_START, Long.MAX_VALUE, Limit.of(PAGE))
                .stream().map(NotificationResponse::from).toList();
    }

    // ── 인박스(V40) ──

    /** 필터 어휘 — 프론트 탭과 1:1 */
    public static final List<String> FILTERS = List.of("all", "unread", "mentions", "assigned", "automation", "snoozed", "archived");
    static final int INBOX_MAX = 100;
    static final java.time.Duration SNOOZE_MAX = java.time.Duration.ofDays(366);
    private static final List<Notification.Type> ALL_TYPES = List.of(Notification.Type.values());
    /** 커서 없음 = 먼 미래부터(PG timestamptz 범위 안) */
    private static final Instant CURSOR_START = Instant.parse("9999-12-31T00:00:00Z");

    @Transactional(readOnly = true)
    public NotificationInboxResponse inbox(long userId, String filter, String cursor, Integer limit) {
        String f = filter == null || filter.isBlank() ? "all" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        if (!FILTERS.contains(f)) {
            throw new IllegalArgumentException("알 수 없는 알림 필터입니다: " + filter + " (" + String.join("·", FILTERS) + ")");
        }
        int size = limit == null ? 50 : limit;
        if (size < 1 || size > INBOX_MAX) throw new IllegalArgumentException("limit은 1~" + INBOX_MAX + " 사이여야 합니다");
        Instant cursorAt = CURSOR_START;
        long cursorId = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.trim().split("_");
            try {
                if (parts.length != 2) throw new NumberFormatException();
                long micros = Long.parseLong(parts[0]);
                cursorAt = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
                cursorId = Long.parseLong(parts[1]);
            } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException e) {
                throw new IllegalArgumentException("잘못된 커서입니다");
            }
        }
        Instant now = now();
        Limit page = Limit.of(size + 1);
        List<Notification> rows = switch (f) {
            case "snoozed" -> notifications.snoozed(userId, now, cursorAt, cursorId, page);
            case "archived" -> notifications.archived(userId, cursorAt, cursorId, page);
            case "unread" -> notifications.inbox(userId, now, ALL_TYPES, true, cursorAt, cursorId, page);
            case "mentions" -> notifications.inbox(userId, now, List.of(Notification.Type.MENTIONED), false, cursorAt, cursorId, page);
            case "assigned" -> notifications.inbox(userId, now, List.of(Notification.Type.ASSIGNED), false, cursorAt, cursorId, page);
            case "automation" -> notifications.inbox(userId, now, List.of(Notification.Type.AUTOMATION), false, cursorAt, cursorId, page);
            default -> notifications.inbox(userId, now, ALL_TYPES, false, cursorAt, cursorId, page);
        };
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            Notification last = rows.get(size - 1);
            next = ChronoUnit.MICROS.between(Instant.EPOCH, last.getSortAt()) + "_" + last.getId();
        }
        return new NotificationInboxResponse(rows.stream().map(NotificationResponse::from).toList(), next,
                notifications.countUnread(userId, now));
    }

    public NotificationBulkResponse bulk(long userId, NotificationBulkRequest request) {
        List<Long> ids = request.ids().stream().filter(Objects::nonNull).distinct().toList();
        String action = request.action().trim().toLowerCase(java.util.Locale.ROOT);
        int updated = switch (action) {
            case "read" -> notifications.markRead(userId, ids);
            case "unread" -> notifications.markUnread(userId, ids);
            case "archive" -> notifications.archive(userId, ids, now());
            case "unarchive" -> notifications.unarchive(userId, ids);
            default -> throw new IllegalArgumentException("알 수 없는 일괄 처리입니다: " + request.action() + " (read·unread·archive·unarchive)");
        };
        return new NotificationBulkResponse(updated, notifications.countUnread(userId, now()));
    }

    public NotificationResponse snooze(long userId, long notificationId, Instant until) {
        Notification notification = requireOwn(userId, notificationId, "미룰");
        Instant now = now();
        if (until == null || !until.isAfter(now)) throw new IllegalArgumentException("미루기 시각은 지금보다 뒤여야 합니다");
        if (until.isAfter(now.plus(SNOOZE_MAX))) throw new IllegalArgumentException("미루기는 1년 뒤까지만 할 수 있습니다");
        if (notification.getArchivedAt() != null) throw new IllegalArgumentException("보관한 알림은 미룰 수 없습니다");
        notification.snooze(until.truncatedTo(ChronoUnit.MICROS));
        return NotificationResponse.from(notification);
    }

    public NotificationResponse unsnooze(long userId, long notificationId) {
        Notification notification = requireOwn(userId, notificationId, "미루기를 취소할");
        notification.unsnooze();
        return NotificationResponse.from(notification);
    }

    public NotificationResponse archive(long userId, long notificationId) {
        Notification notification = requireOwn(userId, notificationId, "보관할");
        notification.archive(now());
        return NotificationResponse.from(notification);
    }

    public NotificationResponse unarchive(long userId, long notificationId) {
        Notification notification = requireOwn(userId, notificationId, "보관을 해제할");
        notification.unarchive();
        return NotificationResponse.from(notification);
    }

    private Notification requireOwn(long userId, long notificationId, String verb) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("알림을 찾을 수 없습니다: " + notificationId));
        if (!notification.getUserId().equals(userId)) {
            throw new ForbiddenException("본인 알림만 " + verb + " 수 있습니다");
        }
        return notification;
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
