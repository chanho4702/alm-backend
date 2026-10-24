package com.platform.almbackend.automation;

import com.platform.almbackend.domain.AutomationRule;
import com.platform.almbackend.domain.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 액션 하나를 실행 주체의 권한으로 적용한다 — 실제 변경은 {@link AutomationIssueOps}가 사람과 같은 경로로 한다 */
@Component
@RequiredArgsConstructor
class AutomationActions {
    private final AutomationIssueOps ops;

    void apply(AutomationRule rule, long issueId, AutomationSpec.Action action) {
        long actor = rule.getActorId();
        switch (action.type()) {
            case AutomationSpec.SET_FIELD -> setField(actor, issueId, action);
            case AutomationSpec.ASSIGN -> {
                Issue issue = ops.require(issueId);
                Long assignee = switch (action.assignee()) {
                    case "reporter" -> issue.getReporterId();
                    case "user" -> action.userId();
                    default -> null;
                };
                ops.edit(actor, issueId, draft -> draft.assigneeId = assignee);
            }
            case AutomationSpec.COMMENT -> ops.comment(actor, issueId, action.body());
            case AutomationSpec.TRANSITION -> ops.transition(actor, issueId, action.toStatus());
            case AutomationSpec.LABELS -> ops.edit(actor, issueId, draft -> {
                draft.labels.removeAll(action.remove());
                for (String label : action.add()) if (!draft.labels.contains(label)) draft.labels.add(label);
            });
            case AutomationSpec.NOTIFY -> ops.notify(actor, issueId, action);
            default -> throw new IllegalArgumentException("모르는 액션입니다: " + action.type());
        }
    }

    private void setField(long actor, long issueId, AutomationSpec.Action action) {
        switch (action.field()) {
            case "flagged" -> ops.flag(actor, issueId, Boolean.TRUE.equals(action.value()));
            case "priority" -> ops.edit(actor, issueId, draft -> draft.priority = String.valueOf(action.value()).trim());
            case "dueDate" -> ops.edit(actor, issueId, draft -> draft.dueDate = AutomationSpec.dateValue(action.value()));
            case "startDate" -> ops.edit(actor, issueId, draft -> {
                draft.startDate = AutomationSpec.dateValue(action.value());
                draft.planningTouched = true;
            });
            case "storyPoints" -> ops.edit(actor, issueId, draft -> {
                draft.storyPoints = AutomationSpec.pointsValue(action.value());
                draft.planningTouched = true;
            });
            default -> throw new IllegalArgumentException("바꿀 수 없는 필드입니다: " + action.field());
        }
    }
}
