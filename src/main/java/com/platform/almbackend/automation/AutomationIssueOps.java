package com.platform.almbackend.automation;

import com.platform.almbackend.collab.CollaborationService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.issue.IssueFlagService;
import com.platform.almbackend.issue.IssueService;
import com.platform.almbackend.issue.IssueTransitionService;
import com.platform.almbackend.issue.dto.IssueTransitionRequest;
import com.platform.almbackend.issue.dto.IssueTransitionsResponse;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssuePlanningRequest;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * 자동화 액션이 이슈를 바꾸는 유일한 창구 — 전부 사람이 쓰는 서비스 경로를 그대로 탄다: 권한(실행 주체의 EDIT)·
 * 워크플로 전환 규칙·필수/순서 검증·변경 이력·활동·알림·Redis 이벤트·도메인 이벤트가 사람의 편집과 같다.
 * 자동화만의 지름길(엔티티 직접 수정)을 두지 않는다 — 그래야 규칙이 사람이 못 하는 일을 하지 못한다.
 */
@Component
@RequiredArgsConstructor
class AutomationIssueOps {
    private final IssueRepository issues;
    private final IssueService issueService;
    private final IssueFlagService flags;
    private final IssueTransitionService transitions;
    private final CollaborationService collaboration;
    private final NotificationService notifications;
    private final jakarta.persistence.EntityManager entityManager;

    /** 편집 초안 — 지금 값으로 채워 두고 액션이 바꾼 것만 다르게 보낸다 */
    static final class Draft {
        String priority;
        String status;
        Long assigneeId;
        LocalDate dueDate;
        LocalDate startDate;
        BigDecimal storyPoints;
        boolean planningTouched;
        List<String> labels;
    }

    Issue require(long issueId) {
        return issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    /** 필드 편집 — 지금 버전을 기대 버전으로 보낸다(같은 트랜잭션 안이라 사이에 끼어들 틈은 행 잠금이 막는다) */
    void edit(long actorId, long issueId, Consumer<Draft> change) {
        Issue current = require(issueId);
        Draft draft = new Draft();
        draft.priority = current.getPriority();
        draft.status = current.getStatus();
        draft.assigneeId = current.getAssigneeId();
        draft.dueDate = current.getDueDate();
        draft.startDate = current.getStartDate();
        draft.storyPoints = current.getStoryPoints();
        draft.labels = new ArrayList<>(current.getLabels());
        change.accept(draft);
        IssueDetailsRequest details = new IssueDetailsRequest(
                current.getParentId(), current.getSprintId(), draft.dueDate, current.getEstimateHours(),
                current.getResolution(), current.getFixVersionId(), List.copyOf(new LinkedHashSet<>(draft.labels)),
                List.copyOf(current.getComponentIds()));
        IssuePlanningRequest planning = draft.planningTouched ? IssuePlanningRequest.of(draft.startDate, draft.storyPoints) : null;
        issueService.update(actorId, issueId, new IssueUpdateRequest(
                current.getTitle(), current.getDescription(), current.getType(), draft.status, draft.priority,
                draft.assigneeId, details, current.getVersion(), null, planning));
        settle();
    }

    /**
     * 액션마다 DB에 내려 둔다 — 다음 액션의 수정 경로가 행을 잠그며 DB 값으로 다시 읽으므로(IssueLocks.refreshForUpdate),
     * 내려 두지 않은 앞 액션의 변경은 그 새로 읽기에 덮여 사라진다. 한 실행의 액션이 같은 트랜잭션이라 되돌리기는 그대로다.
     */
    private void settle() {
        entityManager.flush();
    }

    /**
     * 전환 — 워크플로 엔진의 전환 서비스를 그대로 탄다: 실행 주체로 평가한 가용 전환 중 도착 상태가 같은 첫 전환을 골라
     * 조건·검증·해결 규칙·후처리를 사람이 누른 것과 똑같이 거친다. 전환 화면 값은 보내지 않는다(해결은 기본값).
     * 이미 그 상태면 아무것도 하지 않는다. 갈 수 있는 전환이 없으면 그 이유로 실패한다.
     */
    void transition(long actorId, long issueId, String toStatus) {
        IssueTransitionsResponse available = transitions.available(actorId, issueId);
        if (toStatus.equals(available.status())) return;
        IssueTransitionsResponse.Item chosen = null;
        String reason = null;
        for (IssueTransitionsResponse.Item item : available.transitions()) {
            if (!toStatus.equals(item.to())) continue;
            if (item.available()) {
                chosen = item;
                break;
            }
            if (reason == null) reason = item.unavailableReason();
        }
        if (chosen == null) {
            throw new IllegalArgumentException(reason != null ? reason : "지금 상태에서 " + toStatus + "(으)로 가는 전환이 없습니다");
        }
        transitions.transition(actorId, issueId, new IssueTransitionRequest(chosen.id(), null, null, null));
        settle();
    }

    void flag(long actorId, long issueId, boolean flagged) {
        flags.flag(actorId, issueId, flagged, null);
        settle();
    }

    void comment(long actorId, long issueId, String body) {
        collaboration.addComment(actorId, issueId, body, List.of());
        settle();
    }

    int notify(long actorId, long issueId, AutomationSpec.Action action) {
        Issue issue = require(issueId);
        return notifications.notifyAutomation(actorId, issue,
                action.recipients().contains("assignee"), action.recipients().contains("reporter"),
                action.recipients().contains("watchers"), action.userIds(), action.message());
    }
}
