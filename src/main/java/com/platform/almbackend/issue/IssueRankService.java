package com.platform.almbackend.issue;

import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.settings.SettingsBody;
import com.platform.almbackend.workflow.WorkflowEngine;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueRankRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.sprint.SprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 보드 컬럼 이동과 백로그/스프린트 랭크 — 순서는 사용자가 편집 폼에서 보던 값이 아니므로 version을 올리지
 * 않는다(드래그가 남의 저장을 409로 만들지 않는다). 둘 다 프로젝트 행을 잠그고 한 트랜잭션에서 끝낸다.
 * (2026-10-18 IssueService에서 분리 — 동작 불변)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueRankService {
    private final ProjectService projectService;
    private final SprintService sprintService;
    private final SchemeService settings;
    private final IssueChangeLogService changeLog;
    private final IssueActivityService activityLog;
    private final NotificationService notifications;
    private final EventRelay events;
    private final IssueLocks locks;
    private final IssueRankGroups rankGroups;
    private final IssueService issueService;
    private final WorkflowEngine workflow;
    private final IssueDomainEventPublisher domainEvents;

    /**
     * 보드 컬럼 이동. `sortOrder`는 랭크 그룹(프로젝트+스프린트) 하나에만 존재하는 단일 순서열이고
     * 보드 컬럼은 그 순서열을 상태로 거른 결과다 — 컬럼마다 따로 1부터 매기면 같은 그룹 안에서
     * 번호가 충돌한다. 그래서 컬럼 기준으로 자리를 찾은 뒤 그룹 전체를 다시 매긴다.
     */
    public IssueResponse move(long userId, long issueId, IssueMoveRequest request) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        Issue issue = locks.lockIssue(issueId);
        SettingsBody body = settings.body(issue.getProjectId());
        SettingsBody.ResolvedWorkflow flow = body.workflowFor(issue.getType());
        String status = IssueService.requireStatus(body, issue.getType(), request.status());
        // 트리아지 대기 이슈는 보드에서 열을 옮겨 대기열을 우회하지 못한다(Codex P2) — 워크플로 밖 상태 이관 분기도 포함
        if (!status.equals(issue.getStatus())) WorkflowEngine.assertNotInTriage(issue);
        // 같은 열 안 순서 변경은 전환이 아니다. 지금 상태가 워크플로 밖(옛 데이터)이면 규칙 없이 옮긴다(이관)
        WorkflowEngine.Evaluated used = status.equals(issue.getStatus()) || !flow.has(issue.getStatus())
                ? null : workflow.choose(userId, issue, flow, status);
        String previousStatus = issue.getStatus();
        IssueActivityService.Snapshot moveSnapshot = IssueActivityService.Snapshot.of(issue);
        IssueDomainEventPublisher.Before domainBefore = IssueDomainEventPublisher.Before.of(issue);
        issue.moveTo(status, issue.getSortOrder());
        // 해결(A1): 보드 이동은 해결을 지정하지 않는다 — 완료로 들어가면 기본값, 나가면 비움
        issue.changeResolution(ResolutionRule.resolve(
                SchemeService.isComplete(body, previousStatus), SchemeService.isComplete(body, status),
                issue.getResolution(), false, null, WorkflowEngine.defaultResolution(used)));
        workflow.assertValidators(used, issue);
        // 후처리가 담당자를 바꾸면 편집 폼이 보는 값이 바뀐 것이라 버전을 올린다(남의 열린 폼이 되돌리지 않게)
        if (workflow.applyPostFunctions(used, issue, userId)) issue.bumpVersion();
        List<Issue> group = rankGroups.without(issue, issue.getSprintId());
        int insertAt = IssueRankGroups.indexOfInColumn(group, request.beforeId(), status);
        if (insertAt < 0) insertAt = IssueRankGroups.afterLastOfStatus(group, status);
        group.add(insertAt, issue);
        IssueRankGroups.resequence(group);
        changeLog.recordChanges(userId, issue, previousStatus, issue.getSprintId());
        activityLog.recordUpdate(userId, moveSnapshot, issue);
        // 상태가 실제로 바뀐 이동만 편집과 같은 알림을 탄다. 같은 컬럼 안의 순서 변경은 알리지 않는다.
        // 담당자는 이동으로 바뀌지 않으므로 현재 값을 '이전 담당자'로 넘겨 배정 알림이 나가지 않게 한다.
        if (!Objects.equals(previousStatus, status)) {
            notifications.onIssueUpdated(userId, issue, previousStatus, moveSnapshot.assigneeId());
        }
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        domainEvents.changed(userId, domainBefore, issue, body, used == null || used.transition() == null ? null : used.id());
        return IssueResponse.from(issue);
    }

    /**
     * 백로그/스프린트 랭크 이동 — 대상 그룹(프로젝트+스프린트, 상태 무관) 안에서 자리를 잡는다.
     * 요청 본문이 없으면 백로그 맨 뒤로 본다.
     */
    public IssueResponse rank(long userId, long issueId, IssueRankRequest request) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        Issue issue = locks.lockIssue(issueId);
        Long sprintId = request == null ? null : request.sprintId();
        if (sprintId != null) sprintService.requireSprintInProject(sprintId, issue.getProjectId());
        Long previousSprintId = issue.getSprintId();
        IssueDomainEventPublisher.Before domainBefore = IssueDomainEventPublisher.Before.of(issue);
        List<Issue> source = Objects.equals(previousSprintId, sprintId)
                ? List.of()
                : rankGroups.without(issue, previousSprintId);
        issue.rankTo(sprintId, issue.getSortOrder());
        List<Issue> group = rankGroups.without(issue, sprintId);
        int insertAt = IssueRankGroups.indexOf(group, request == null ? null : request.beforeId());
        if (insertAt < 0) insertAt = group.size();
        group.add(insertAt, issue);
        IssueRankGroups.resequence(group);
        // 떠난 그룹도 다시 조밀하게 만든다 — 그룹이 항상 1..n이면 이후 삽입 위치 계산이 단순하다.
        IssueRankGroups.resequence(source);
        changeLog.recordChanges(userId, issue, issue.getStatus(), previousSprintId);
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        // 랭크는 상태를 바꾸지 않는다 — 본문(상태 의미)이 필요 없다
        domainEvents.changed(userId, domainBefore, issue, null, null);
        return IssueResponse.from(issue);
    }
}
