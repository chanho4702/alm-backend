package com.platform.almbackend.issue;

import com.platform.almbackend.collab.CollaborationService;
import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueTransitionRequest;
import com.platform.almbackend.issue.dto.IssueTransitionsResponse;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.settings.SettingsBody;
import com.platform.almbackend.version.VersionService;
import com.platform.almbackend.workflow.WorkflowEngine;
import com.platform.common.error.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 가용 전환 조회(A2)와 전환 실행(A4·C1~C3). 실행 순서는 지라와 같다: 조건 → 전환 화면 값 반영 → 검증 → 상태 변경 +
 * 해결 규칙 → 후처리 → 코멘트. 한 트랜잭션이라 어느 단계에서 거부돼도 아무것도 남지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueTransitionService {
    private final ProjectService projectService;
    private final SchemeService settings;
    private final WorkflowEngine workflow;
    private final IssueLocks locks;
    private final IssueRankGroups rankGroups;
    private final VersionService versionService;
    private final CollaborationService collaboration;
    private final IssueChangeLogService changeLog;
    private final IssueActivityService activityLog;
    private final NotificationService notifications;
    private final EventRelay events;
    private final IssueDomainEventPublisher domainEvents;

    @Transactional(readOnly = true)
    public IssueTransitionsResponse available(long userId, long issueId) {
        Issue issue = locks.requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        SettingsBody body = settings.body(issue.getProjectId());
        SettingsBody.ResolvedWorkflow flow = body.workflowFor(issue.getType());
        List<IssueTransitionsResponse.Item> items = new ArrayList<>();
        for (WorkflowEngine.Evaluated e : workflow.evaluate(userId, issue, flow)) {
            SettingsBody.WorkflowStatus target = flow.status(e.to()).orElse(null);
            items.add(new IssueTransitionsResponse.Item(
                    e.id(), e.name(), e.to(),
                    target == null ? e.to() : target.name(),
                    target == null ? null : target.kind(),
                    target == null ? null : target.icon(),
                    e.global(), e.available(), e.reason(),
                    !e.screenFields().isEmpty(), e.screenFields(), e.validators(),
                    workflow.missingFields(e, issue)));
        }
        return new IssueTransitionsResponse(issue.getId(), issue.getStatus(), issue.getVersion(), flow.typeSpecific(), items);
    }

    public IssueResponse transition(long userId, long issueId, IssueTransitionRequest request) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        Issue issue = locks.lockIssue(issueId);
        if (request.expectedVersion() != null && !issue.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 이슈를 수정했습니다. 현재 "
                    + issue.getVersion() + ", 요청 " + request.expectedVersion());
        }
        SettingsBody body = settings.body(issue.getProjectId());
        SettingsBody.ResolvedWorkflow flow = body.workflowFor(issue.getType());
        WorkflowEngine.Evaluated used = workflow.chooseById(userId, issue, flow, request.transitionId());

        IssueTransitionRequest.Fields fields = request.fieldsOrEmpty();
        List<String> screen = used.screenFields();
        for (String sent : fields.sent()) {
            if (!screen.contains(sent)) throw new IllegalArgumentException("이 전환 화면에 없는 칸입니다: " + SettingsBody.fieldName(sent));
        }
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (!comment.isEmpty() && !screen.contains("comment")) {
            throw new IllegalArgumentException("이 전환 화면에는 코멘트 칸이 없습니다");
        }

        String previousStatus = issue.getStatus();
        Long previousSprintId = issue.getSprintId();
        Long previousAssigneeId = issue.getAssigneeId();
        Long previousFixVersionId = issue.getFixVersionId();
        LocalDate previousDueDate = issue.getDueDate();
        IssueActivityService.Snapshot activityBefore = IssueActivityService.Snapshot.of(issue);
        IssueDomainEventPublisher.Before domainBefore = IssueDomainEventPublisher.Before.of(issue);

        // 전환 화면 값
        if (fields.has("assignee")) issue.assignTo(fields.getAssigneeId());
        if (fields.has("dueDate")) issue.changeDueDate(fields.getDueDate());
        if (fields.has("fixVersion")) {
            Long versionId = fields.getFixVersionId();
            if (versionId != null && !Objects.equals(versionId, issue.getFixVersionId())) {
                versionService.requireAssignable(versionId, issue.getProjectId());
            }
            issue.assignFixVersion(versionId);
        }

        // 상태 + 해결(A1). 상태가 바뀌면 보드 열 맨 뒤로(PUT 수정과 같다)
        String status = used.to();
        issue.moveTo(status, issue.getSortOrder());
        if (!Objects.equals(previousStatus, status)) {
            List<Issue> group = rankGroups.without(issue, issue.getSprintId());
            group.add(IssueRankGroups.afterLastOfStatus(group, status), issue);
            IssueRankGroups.resequence(group);
        }
        IssueResolution resolution = ResolutionRule.resolve(
                SchemeService.isComplete(body, previousStatus), SchemeService.isComplete(body, status),
                issue.getResolution(), fields.has("resolution"), fields.getResolution(),
                WorkflowEngine.defaultResolution(used));
        issue.changeResolution(resolution);

        workflow.assertValidators(used, issue);
        workflow.applyPostFunctions(used, issue, userId);

        // 편집 폼이 보는 값이 바뀌었으면 버전을 올린다 — 상태·해결만 바뀐 전환은 보드 이동처럼 그대로
        if (!Objects.equals(previousAssigneeId, issue.getAssigneeId())
                || !Objects.equals(previousFixVersionId, issue.getFixVersionId())
                || !Objects.equals(previousDueDate, issue.getDueDate())) {
            issue.bumpVersion();
        }

        changeLog.recordChanges(userId, issue, previousStatus, previousSprintId);
        activityLog.recordUpdate(userId, activityBefore, issue);
        notifications.onIssueUpdated(userId, issue, previousStatus, previousAssigneeId);
        if (!comment.isEmpty()) collaboration.addComment(userId, issue.getId(), comment, List.of());
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        domainEvents.changed(userId, domainBefore, issue, body, used.transition() == null ? null : used.id());
        return IssueResponse.from(issue);
    }
}
