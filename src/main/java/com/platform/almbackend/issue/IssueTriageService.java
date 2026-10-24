package com.platform.almbackend.issue;

import com.platform.almbackend.collab.CollaborationService;
import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.collab.IssueLinkService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.settings.SettingsBody;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 트리아지(V35, Linear식) — 일반 워크플로 밖 대기열. 새 상태 분류를 만들지 않는다(kind 3개 고정이 확정 사항):
 * 대기열은 {@code issue.triage_state}이고, 이슈의 상태는 그동안 워크플로 첫 상태로 둔다.
 *
 * <ul>
 *   <li>수락: 대기열에서 빼 지정 상태(기본 첫 '할 일')로 백로그 끝에 넣는다.</li>
 *   <li>중복: "중복" 링크(이 이슈 → 원본) + 해결 DUPLICATE + 첫 완료 상태.</li>
 *   <li>거절: 해결 WONT_DO("하지 않음") + 첫 완료 상태 — Linear의 Canceled를 해결 사유로 표현한다(기획 §7-2).</li>
 *   <li>보류: 지정 시각까지 대기열에서 접어 둔다. 시각이 지나면 다시 대기로 보인다.</li>
 * </ul>
 * 처리는 프로젝트 EDIT(멤버)다. 대기열을 보는 것은 VIEW다(올린 사람도 자기 이슈가 어떻게 됐는지 본다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueTriageService {

    /** 보류 상한 — 그보다 먼 보류는 사실상 거절이다 */
    private static final Duration MAX_SNOOZE = Duration.ofDays(366);
    private static final String DUPLICATES = "duplicates";
    private static final String NOT_IN_TRIAGE = "트리아지 대기열에 없는 이슈입니다";

    private final IssueLocks locks;
    private final IssueRepository issues;
    private final ProjectService projects;
    private final SchemeService settings;
    private final IssueActivityService activityLog;
    private final CollaborationService collaboration;
    private final IssueLinkService links;
    private final LinkTypeDefRepository linkTypes;
    private final NotificationService notifications;
    private final IssueChangeLogService changeLog;
    private final EventRelay events;
    private final IssueDomainEventPublisher domainEvents;

    @Schema(description = "프로젝트 트리아지 설정과 대기열 크기")
    public record TriageSettings(
            @Schema(example = "7") long projectId,
            @Schema(description = "트리아지를 켰는가 — 켜면 편집 권한 없는 열람자도 이슈를 올릴 수 있고 대기열로 간다") boolean enabled,
            @Schema(description = "대기 중(보류가 풀린 것 포함)", example = "4") long pending,
            @Schema(description = "보류 중", example = "1") long snoozed) {}

    @Schema(description = "수락 — 비운 항목은 그대로(상태는 워크플로 첫 '할 일')")
    public record AcceptRequest(
            @Schema(description = "들어갈 상태. 완료 의미 상태는 안 된다", example = "todo") String status,
            @Schema(description = "담당자(선택)", example = "42") Long assigneeId,
            @Schema(description = "우선순위(선택)", example = "high") String priority,
            @Schema(description = "코멘트(선택)") String comment) {}

    @Schema(description = "중복 표시")
    public record DuplicateRequest(
            @Schema(description = "원본 이슈 ID", example = "1001") Long duplicateOfIssueId,
            @Schema(description = "코멘트(선택)") String comment) {}

    @Schema(description = "거절")
    public record DeclineRequest(@Schema(description = "거절 이유(선택) — 코멘트로 남는다") String comment) {}

    @Schema(description = "보류")
    public record SnoozeRequest(@Schema(description = "다시 대기로 보일 시각(미래, 최대 1년)", example = "2026-11-01T00:00:00Z") Instant until) {}

    // ── 설정 · 목록 ──

    @Transactional(readOnly = true)
    public TriageSettings settings(long userId, long projectId) {
        projects.require(userId, projectId, AlmAction.VIEW);
        return settingsOf(projects.requireProject(projectId));
    }

    public TriageSettings updateSettings(long userId, long projectId, boolean enabled) {
        projects.require(userId, projectId, AlmAction.ADMIN);
        Project project = locks.lockProject(projectId);
        project.setTriageEnabled(enabled);
        // 끈다고 대기열 이슈를 버리지 않는다 — 남은 것은 계속 처리할 수 있고 새로 들어오지만 않는다
        return settingsOf(project);
    }

    /** 대기열 — 들어온 순서(오래된 것 먼저). state = pending(기본)·snoozed·all */
    @Transactional(readOnly = true)
    public List<IssueResponse> queue(long userId, long projectId, String state) {
        projects.require(userId, projectId, AlmAction.VIEW);
        String wanted = state == null || state.isBlank() ? "pending" : state.trim().toLowerCase(Locale.ROOT);
        if (!List.of("pending", "snoozed", "all").contains(wanted)) {
            throw new IllegalArgumentException("트리아지 보기는 pending·snoozed·all 중 하나입니다");
        }
        Instant now = Instant.now();
        return issues.findByProjectIdAndTriageStateIsNotNullOrderByCreatedAtAscIdAsc(projectId).stream()
                .filter(issue -> switch (wanted) {
                    case "pending" -> Issue.TRIAGE_PENDING.equals(issue.effectiveTriageState(now));
                    case "snoozed" -> Issue.TRIAGE_SNOOZED.equals(issue.effectiveTriageState(now));
                    default -> true;
                })
                .map(IssueResponse::from)
                .toList();
    }

    // ── 대기열로 · 처리 ──

    /** 기존 이슈를 대기열로 보낸다(MANUAL) — 스프린트에서 빠져 백로그 끝에 놓인다 */
    public IssueResponse send(long userId, long issueId) {
        Issue issue = lock(userId, issueId);
        if (!projects.requireProject(issue.getProjectId()).isTriageEnabled()) {
            throw new IllegalArgumentException("트리아지를 켜지 않은 프로젝트입니다");
        }
        if (issue.inTriage()) throw new IllegalArgumentException("이미 트리아지 대기열에 있습니다");
        if (settings.isComplete(issue.getProjectId(), issue.getStatus())) {
            throw new IllegalArgumentException("완료된 이슈는 트리아지로 보낼 수 없습니다");
        }
        Before before = Before.of(issue);
        issue.sendToTriage(Issue.TRIAGE_MANUAL, backlogEnd(issue.getProjectId()));
        return finish(userId, issue, before, "트리아지로 보냄", null);
    }

    public IssueResponse accept(long userId, long issueId, AcceptRequest request) {
        AcceptRequest body = request == null ? new AcceptRequest(null, null, null, null) : request;
        Issue issue = lockTriaged(userId, issueId);
        long projectId = issue.getProjectId();
        SettingsBody workflow = settings.body(projectId);
        String status = body.status() == null || body.status().isBlank()
                ? SchemeService.defaultStatus(workflow, issue.getType()) : body.status().trim();
        SchemeService.assertValidStatus(workflow, issue.getType(), status);
        if (settings.isComplete(projectId, status)) {
            throw new IllegalArgumentException("수락은 진행할 상태로만 할 수 있습니다 — 끝내려면 거절이나 중복을 쓰세요");
        }
        Before before = Before.of(issue);
        issue.acceptFromTriage(status, backlogEnd(projectId), body.assigneeId(),
                body.priority() == null ? null : settings.resolvePriority(projectId, body.priority()));
        return finish(userId, issue, before, "수락", body.comment());
    }

    public IssueResponse decline(long userId, long issueId, DeclineRequest request) {
        Issue issue = lockTriaged(userId, issueId);
        Before before = Before.of(issue);
        issue.closeFromTriage(firstCompleteStatus(issue), IssueResolution.WONT_DO);
        return finish(userId, issue, before, "거절(하지 않음)", request == null ? null : request.comment());
    }

    public IssueResponse duplicate(long userId, long issueId, DuplicateRequest request) {
        if (request == null || request.duplicateOfIssueId() == null) {
            throw new IllegalArgumentException("원본 이슈를 지정하세요");
        }
        if (request.duplicateOfIssueId() == issueId) throw new IllegalArgumentException("자기 자신의 중복일 수는 없습니다");
        Issue issue = lockTriaged(userId, issueId);
        Issue original = issues.findById(request.duplicateOfIssueId())
                .orElseThrow(() -> new com.platform.common.error.NotFoundException("원본 이슈를 찾을 수 없습니다"));
        Before before = Before.of(issue);
        issue.closeFromTriage(firstCompleteStatus(issue), IssueResolution.DUPLICATE);
        // 링크는 원본 VIEW를 다시 확인한다(남의 프로젝트 이슈를 끌어와 읽는 길을 막는 규칙 그대로)
        if (linkTypes.existsById(DUPLICATES)) links.addLink(userId, issueId, original.getId(), DUPLICATES);
        return finish(userId, issue, before, "중복: " + original.getKey(), request.comment());
    }

    public IssueResponse snooze(long userId, long issueId, SnoozeRequest request) {
        Instant now = Instant.now();
        Instant until = request == null ? null : request.until();
        if (until == null || !until.isAfter(now)) throw new IllegalArgumentException("보류는 미래 시각까지로 지정하세요");
        if (until.isAfter(now.plus(MAX_SNOOZE))) throw new IllegalArgumentException("보류는 1년까지만 할 수 있습니다");
        Issue issue = lockTriaged(userId, issueId);
        Before before = Before.of(issue);
        issue.snoozeTriage(until.truncatedTo(ChronoUnit.MICROS));
        return finish(userId, issue, before, "보류(" + until.truncatedTo(ChronoUnit.MINUTES) + "까지)", null);
    }

    // ── 공통 ──

    private Issue lock(long userId, long issueId) {
        Issue snapshot = locks.requireIssue(issueId);
        projects.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        return locks.lockIssue(issueId);
    }

    private Issue lockTriaged(long userId, long issueId) {
        Issue issue = lock(userId, issueId);
        if (!issue.inTriage()) throw new IllegalArgumentException(NOT_IN_TRIAGE);
        return issue;
    }

    private IssueResponse finish(long userId, Issue issue, Before before, String detail, String comment) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        activityLog.record(issue.getId(), userId, "triage", detail, now);
        changeLog.recordChanges(userId, issue, before.status(), before.sprintId());
        notifications.onIssueUpdated(userId, issue, before.status(), before.assigneeId());
        if (comment != null && !comment.isBlank()) collaboration.addComment(userId, issue.getId(), comment.trim(), List.of());
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        domainEvents.changed(userId, before.fields(), issue, settings.body(issue.getProjectId()), null);
        return IssueResponse.from(issue);
    }

    private record Before(String status, Long sprintId, Long assigneeId, IssueDomainEventPublisher.Before fields) {
        static Before of(Issue issue) {
            return new Before(issue.getStatus(), issue.getSprintId(), issue.getAssigneeId(), IssueDomainEventPublisher.Before.of(issue));
        }
    }

    private long backlogEnd(long projectId) {
        return issues.findMaxSortOrderInRankGroup(projectId, null) + 1;
    }

    /** 그 이슈 타입 워크플로(C5)에서 순서상 첫 완료 의미 상태 — 없으면 끝낼 수 없다 */
    private String firstCompleteStatus(Issue issue) {
        return settings.body(issue.getProjectId()).workflowFor(issue.getType()).statuses().stream()
                .filter(s -> "complete".equals(s.kind()))
                .min(Comparator.comparingInt(SettingsBody.WorkflowStatus::order))
                .map(SettingsBody.WorkflowStatus::id)
                .orElseThrow(() -> new IllegalArgumentException("이 프로젝트 워크플로에 완료 상태가 없습니다"));
    }

    private TriageSettings settingsOf(Project project) {
        Instant now = Instant.now();
        long pending = 0;
        long snoozed = 0;
        for (Issue issue : issues.findByProjectIdAndTriageStateIsNotNullOrderByCreatedAtAscIdAsc(project.getId())) {
            if (Objects.equals(Issue.TRIAGE_SNOOZED, issue.effectiveTriageState(now))) snoozed++;
            else pending++;
        }
        return new TriageSettings(project.getId(), project.isTriageEnabled(), pending, snoozed);
    }
}
