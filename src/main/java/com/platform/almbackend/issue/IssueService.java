package com.platform.almbackend.issue;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.workflow.WorkflowEngine;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.settings.SettingsBody;
import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.component.ComponentService;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.sprint.SprintService;
import com.platform.almbackend.version.VersionService;
import com.platform.almbackend.attachment.AttachmentService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.triage.TriageAdmission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * 이슈 생성·조회·수정·삭제. 보드 이동·랭크는 {@link IssueRankService}, CSV 가져오기는 {@link IssueImportService},
 * 일괄 편집은 {@link IssueBulkService}, 행 잠금은 {@link IssueLocks}가 맡는다(2026-10-18 분할 — 동작 불변).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueService {
    private final IssueRepository issues;
    private final ProjectService projectService;
    private final SprintService sprintService;
    private final VersionService versionService;
    private final AttachmentService attachmentService;
    private final EventRelay events;
    private final IssueChangeLogService changeLog;
    private final NotificationService notifications;
    private final SchemeService settings;
    private final IssueActivityService activityLog;
    private final ComponentService componentService;
    private final IssueLocks locks;
    private final IssueRankGroups rankGroups;
    private final WorkflowEngine workflow;
    private final IssueDomainEventPublisher domainEvents;
    private final com.platform.almbackend.planning.ProjectPlanningService planningSettings;
    private final TriageAdmission triageAdmission;

    @Transactional(readOnly = true)
    public List<IssueResponse> list(long userId, long projectId) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        // 트리아지 대기열(V35)은 일반 워크플로 밖이다 — 보드·백로그가 읽는 목록에 넣지 않는다(대기열은 /triage)
        return issues.findByProjectIdOrderBySortOrderAscKeyAsc(projectId).stream()
                .filter(issue -> !issue.inTriage())
                .map(IssueResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse get(long userId, long issueId) {
        Issue issue = locks.requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return IssueResponse.from(issue);
    }

    public IssueResponse create(long userId, long projectId, IssueCreateRequest request) {
        // 트리아지를 켠 프로젝트는 열람자도 올릴 수 있다(대기열행). 꺼져 있으면 예전처럼 EDIT가 필요하다
        String triageSource = triageAdmission.admit(userId, projectId, request.triage());
        Project project = locks.lockProject(projectId);
        return createNumbered(userId, project, project.nextIssueNumber(), request, triageSource);
    }

    /**
     * 번호가 정해진 생성 — {@link #create}와 CSV 가져오기({@link IssueImportService})가 함께 쓴다. 호출자가 프로젝트
     * 행을 잠근 트랜잭션 안에서 불러야 한다(번호 카운터의 직렬화 지점).
     */
    public IssueResponse createNumbered(long userId, Project project, long number, IssueCreateRequest request) {
        return createNumbered(userId, project, number, request, null);
    }

    /** {@code triageSource}가 있으면 트리아지 대기열로 만든다 — 스프린트에는 넣지 않는다(대기열은 계획 밖) */
    private IssueResponse createNumbered(long userId, Project project, long number, IssueCreateRequest request,
                                         String triageSource) {
        long projectId = project.getId();
        String type = normalizeType(request.type() == null ? settings.defaultType(projectId) : request.type());
        settings.assertTypeEnabled(projectId, type);
        IssueDetailsRequest details = request.details();
        SettingsBody body = settings.body(projectId);
        assertRequiredFields(body, type, request, details);
        Long parentId = details == null ? null : details.parentId();
        validateParent(projectId, null, type, parentId);
        Long sprintId = triageSource != null || details == null ? null : details.sprintId();
        if (sprintId != null) sprintService.requireSprintInProject(sprintId, projectId);
        IssuePlanning.Values planning = IssuePlanning.merge(request.planning(), null, null);
        IssuePlanning.requireOrder(planning.startDate(), details == null ? null : details.dueDate());
        // 추정 척도(V39) — 척도 밖 포인트는 400
        planningSettings.assertPoints(projectId, planning.storyPoints());
        long order = issues.findMaxSortOrderInRankGroup(projectId, sprintId) + 1;
        List<Long> componentIds = componentService.validateForIssue(projectId, details == null ? null : details.componentIds());
        Issue fresh = Issue.of(
                projectId,
                number,
                project.getKey() + "-" + number,
                request.title().trim(),
                normalizeDescription(request.description()),
                type,
                requireStatus(body, type, request.status()),
                settings.resolvePriority(projectId, request.priority()),
                request.assigneeId() == null ? componentService.resolveDefaultAssignee(project, componentIds) : request.assigneeId(),
                userId,
                parentId,
                sprintId,
                details == null ? null : details.dueDate(),
                details == null ? null : details.estimateHours(),
                normalizeLabels(details == null ? null : details.labels()),
                order);
        fresh.replaceComponents(componentIds);
        // 완료 의미 상태로 만들면 해결 = 완료(A1, 목업 createIssue와 같다)
        fresh.changeResolution(ResolutionRule.resolve(false, SchemeService.isComplete(body, fresh.getStatus()),
                null, false, null, null));
        fresh.applyPlanning(planning.startDate(), planning.storyPoints());
        if (triageSource != null) fresh.sendToTriage(triageSource, order);
        Long fixVersionId = details == null ? null : details.fixVersionId();
        if (fixVersionId != null) {
            versionService.requireAssignable(fixVersionId, projectId);
            fresh.assignFixVersion(fixVersionId);
        }
        Issue issue = issues.save(fresh);
        changeLog.recordCreated(userId, issue);
        activityLog.recordCreated(userId, issue);
        notifications.onIssueCreated(userId, issue);
        notifications.notifyMentioned(userId, issue, request.mentionedUserIds(), Instant.now());
        events.afterCommit(AlmEvents.issueCreated(userId, issue));
        domainEvents.created(userId, issue);
        return IssueResponse.from(issue);
    }

    /**
     * 요청의 이슈 타입으로 해석한 필드 구성에서 필수로 지정된 필드는 생성 시 값이 있어야 한다(CSV 가져오기도
     * 같은 경로를 탄다). 수정(PUT)에서는 검사하지 않는다 —
     * 구성이 바뀌었다고 기존 이슈 편집을 막지 않기 위해서다. 우선순위는 기본값이 항상 있어 여기서 막지 않고,
     * 해결·상위 항목은 필수로 지정할 수 없다(스킴 저장에서 거부). 첨부·링크는 생성 이후에 붙는 값이라 검사 대상이 아니다.
     */
    /** 설명은 TipTap HTML로 온다 — `<p></p>`처럼 태그만 있는 빈 본문은 비어 있는 것으로 본다(프론트 isEmptyHtml과 같은 판정) */
    public static boolean hasText(String html) {
        if (html == null) return false;
        String text = html.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ");
        return !text.isBlank();
    }

    private static void assertRequiredFields(SettingsBody config, String typeId, IssueCreateRequest request,
                                             IssueDetailsRequest details) {
        Map<String, SettingsBody.FieldConfig> fields = config.fieldsById(typeId);
        requireField(fields, "description", hasText(request.description()));
        requireField(fields, "assignee", request.assigneeId() != null);
        requireField(fields, "labels", details != null && details.labels() != null && !details.labels().isEmpty());
        requireField(fields, "components", details != null && details.componentIds() != null && !details.componentIds().isEmpty());
        requireField(fields, "sprint", details != null && details.sprintId() != null);
        requireField(fields, "dueDate", details != null && details.dueDate() != null);
        requireField(fields, "fixVersion", details != null && details.fixVersionId() != null);
        requireField(fields, "estimate", details != null && details.estimateHours() != null);
        requireField(fields, "startDate", request.planning() != null && request.planning().getStartDate() != null);
        requireField(fields, "storyPoints", request.planning() != null && request.planning().getStoryPoints() != null);
    }

    private static void requireField(Map<String, SettingsBody.FieldConfig> fields, String fieldId, boolean present) {
        SettingsBody.FieldConfig field = fields.get(fieldId);
        if (!present && field != null && field.required()) {
            throw new IllegalArgumentException(SettingsBody.requiredMessage(fieldId));
        }
    }

    public IssueResponse update(long userId, long issueId, IssueUpdateRequest request) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        Issue issue = locks.lockIssue(issueId);
        if (!issue.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 이슈를 수정했습니다. 현재 "
                    + issue.getVersion() + ", 요청 " + request.expectedVersion());
        }
        String nextType = normalizeType(request.type() == null ? issue.getType() : request.type());
        if (!nextType.equals(issue.getType())) settings.assertTypeEnabled(issue.getProjectId(), nextType);
        IssueDetailsRequest details = request.details();
        Long requestedParentId = details == null ? issue.getParentId() : details.parentId();
        Long parentId = resolveParent(issue, nextType, requestedParentId);
        Long sprintId = details == null ? issue.getSprintId() : details.sprintId();
        if (sprintId != null && !Objects.equals(sprintId, issue.getSprintId())) {
            sprintService.requireSprintInProject(sprintId, issue.getProjectId());
        }
        LocalDate dueDate = details == null ? issue.getDueDate() : details.dueDate();
        BigDecimal estimateHours = details == null ? issue.getEstimateHours() : details.estimateHours();
        Long fixVersionId = details == null ? issue.getFixVersionId() : details.fixVersionId();
        if (fixVersionId != null && !Objects.equals(fixVersionId, issue.getFixVersionId())) {
            versionService.requireAssignable(fixVersionId, issue.getProjectId());
        }
        List<String> labels = details == null
                ? List.copyOf(issue.getLabels())
                : normalizeLabels(details.labels());
        List<Long> componentIds = details == null || details.componentIds() == null
                ? List.copyOf(issue.getComponentIds())
                : componentService.validateForIssue(issue.getProjectId(), details.componentIds());
        IssuePlanning.Values planning = IssuePlanning.merge(request.planning(), issue.getStartDate(), issue.getStoryPoints());
        IssuePlanning.requireOrder(planning.startDate(), dueDate);
        // 추정 척도(V39) — 값이 바뀔 때만 본다(척도를 바꿔도 손대지 않은 옛 값은 그대로 저장된다)
        if (planning.storyPoints() != null && (issue.getStoryPoints() == null
                || planning.storyPoints().compareTo(issue.getStoryPoints()) != 0)) {
            planningSettings.assertPoints(issue.getProjectId(), planning.storyPoints());
        }
        SettingsBody body = settings.body(issue.getProjectId());
        SettingsBody.ResolvedWorkflow flow = body.workflowFor(nextType);
        String requested = request.status() == null ? issue.getStatus() : request.status().trim();
        String status;
        WorkflowEngine.Evaluated used = null;
        if (!flow.has(issue.getStatus()) && requested.equals(issue.getStatus())) {
            // 타입을 바꿨더니 새 타입 워크플로(C5)에 지금 상태가 없다 — 설정 변경 이관과 같은 규칙으로 옮긴다.
            // 화면은 미리 대상 상태를 물어 status로 보낼 수 있다(그 경우 아래 분기에서 그 값이 검증된다).
            status = settings.mappedStatus(
                    body.statuses().stream().filter(x -> x.id().equals(issue.getStatus())).findFirst().orElse(null),
                    issue.getStatus(), flow.statuses());
        } else {
            SchemeService.assertValidStatus(body, nextType, requested);
            status = requested;
            // 전환 규칙(조건 포함)은 지금 상태가 그 워크플로 안에 있을 때만 — 밖이면 타입 변경에 따른 이관이다
            if (!status.equals(issue.getStatus()) && flow.has(issue.getStatus())) {
                used = workflow.choose(userId, issue, flow, status);
            }
        }
        // 해결(A1) — 화면이 들고 있던 값을 그대로 다시 보내므로 "현재 값과 다를 때만" 지정으로 본다
        boolean explicitResolution = details != null && !Objects.equals(details.resolution(), issue.getResolution());
        // 트리아지 대기 이슈의 상태·해결은 트리아지 처리(수락·중복·거절)로만 — 요청이 바꾸려 하면 400(Codex P2).
        // 타입 변경에 따른 상태 이관(요청 상태 = 지금 상태)은 사용자가 고른 전환이 아니므로 막지 않는다.
        if (!requested.equals(issue.getStatus()) || explicitResolution) WorkflowEngine.assertNotInTriage(issue);
        IssueResolution resolution = ResolutionRule.resolve(
                SchemeService.isComplete(body, issue.getStatus()), SchemeService.isComplete(body, status),
                issue.getResolution(), explicitResolution, details == null ? null : details.resolution(),
                WorkflowEngine.defaultResolution(used));
        // 상태나 스프린트가 바뀌면 대상 컬럼 맨 뒤로 보낸다. 정밀 배치는 move/rank가 한다.
        Long previousSprintId = issue.getSprintId();
        String previousStatus = issue.getStatus();
        Long previousAssigneeId = issue.getAssigneeId();
        IssueActivityService.Snapshot snapshotBefore = IssueActivityService.Snapshot.of(issue);
        IssueDomainEventPublisher.Before domainBefore = IssueDomainEventPublisher.Before.of(issue);
        java.time.LocalDate previousStart = issue.getStartDate();
        BigDecimal previousPoints = issue.getStoryPoints();
        boolean regrouped = !Objects.equals(status, issue.getStatus())
                || !Objects.equals(sprintId, previousSprintId);
        long order = issue.getSortOrder();
        issue.edit(
                request.title().trim(),
                normalizeDescription(request.description()),
                nextType,
                status,
                request.priority() == null ? null : settings.resolvePriority(snapshot.getProjectId(), request.priority()),
                request.assigneeId(),
                parentId,
                sprintId,
                dueDate,
                estimateHours,
                resolution,
                fixVersionId,
                labels,
                order);
        issue.replaceComponents(componentIds);
        issue.applyPlanning(planning.startDate(), planning.storyPoints());
        // 전환 검증은 요청 값을 반영한 뒤, 후처리는 검증 뒤(지라와 같은 순서)
        workflow.assertValidators(used, issue);
        workflow.applyPostFunctions(used, issue, userId);
        if (regrouped) {
            List<Issue> source = Objects.equals(previousSprintId, sprintId)
                    ? List.of()
                    : rankGroups.without(issue, previousSprintId);
            List<Issue> group = rankGroups.without(issue, sprintId);
            group.add(IssueRankGroups.afterLastOfStatus(group, status), issue);
            IssueRankGroups.resequence(group);
            IssueRankGroups.resequence(source);
        }
        changeLog.recordChanges(userId, issue, previousStatus, previousSprintId);
        activityLog.recordUpdate(userId, snapshotBefore, issue);
        IssuePlanning.recordActivity(activityLog, userId, issue, previousStart, previousPoints);
        notifications.onIssueUpdated(userId, issue, previousStatus, previousAssigneeId);
        notifications.notifyMentioned(userId, issue, request.mentionedUserIds(), Instant.now());
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        domainEvents.changed(userId, domainBefore, issue, body, used == null || used.transition() == null ? null : used.id());
        return IssueResponse.from(issue);
    }

    public void delete(long userId, long issueId) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        locks.lockProject(snapshot.getProjectId());
        Issue issue = locks.lockIssue(issueId);
        events.afterCommit(AlmEvents.issueDeleted(userId, issue));
        domainEvents.deleted(userId, issue, false);
        issues.clearParentByParentId(issueId);
        attachmentService.deleteAllForIssue(issueId);
        issues.delete(issue);
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    /** 상태 id는 프로젝트 워크플로에 있어야 한다. 비면 워크플로의 첫 '할 일' 상태 */
    public String requireStatus(long projectId, String value) {
        return requireStatus(settings.body(projectId), null, value);
    }

    /** 이슈 타입의 워크플로(C5, 없으면 프로젝트 기본) 기준 — 비면 그 워크플로의 첫 '할 일' 상태 */
    public static String requireStatus(SettingsBody body, String typeId, String value) {
        String status = value == null || value.isBlank() ? SchemeService.defaultStatus(body, typeId) : value.trim();
        SchemeService.assertValidStatus(body, typeId, status);
        return status;
    }

    /** 옛 클라이언트의 대문자 enum 이름(TASK)도 레지스트리 id(task)로 받는다 */
    private static String normalizeType(String value) {
        return value == null ? "task" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Long resolveParent(Issue issue, String newType, Long requestedParentId) {
        if (requestedParentId == null) return null;
        validateParent(issue.getProjectId(), issue.getId(), newType, requestedParentId);
        return requestedParentId;
    }

    /**
     * 계층 깊이 제한 없음 — 같은 프로젝트의 어떤 이슈든 상위 항목이 될 수 있다(하위의 하위 허용).
     * 막는 것은 다른 프로젝트·자기 자신·순환(자신의 자손을 상위로 지정)뿐이다.
     */
    private void validateParent(long projectId, Long issueId, String type, Long parentId) {
        if (parentId == null) return;
        Issue parent = requireParent(parentId);
        if (!Objects.equals(parent.getProjectId(), projectId)) {
            throw new IllegalArgumentException("같은 프로젝트의 이슈만 상위 항목으로 지정할 수 있습니다");
        }
        if (issueId != null && (Objects.equals(parent.getId(), issueId) || isDescendant(issueId, parent))) {
            throw new IllegalArgumentException("상위 항목이 순환합니다");
        }
    }

    private Issue requireParent(long parentId) {
        return issues.findById(parentId)
                .orElseThrow(() -> new NotFoundException("부모 이슈를 찾을 수 없습니다: " + parentId));
    }

    /**
     * candidate가 ancestorId의 자손인지 — 부모 체인을 재귀 CTE 한 번으로 올라간다(감사 A-20: 계층 무제한이라
     * 예전처럼 단계마다 findById를 부르면 깊이만큼 질의가 나갔다).
     */
    private boolean isDescendant(long ancestorId, Issue candidate) {
        if (candidate.getParentId() == null) return false;
        int result = issues.ancestorChainCheck(candidate.getId(), ancestorId);
        if (result == 1) {
            // 상한에서 멈춘 체인은 "순환 없음"이 아니라 "모름"이다 — 통과시키지 않는다
            throw new IllegalArgumentException("상위 항목 계층이 너무 깊어 순환 여부를 확인할 수 없습니다 (최대 "
                    + IssueRepository.MAX_ANCESTOR_DEPTH + "단계)");
        }
        return result == 2;
    }

    private static List<String> normalizeLabels(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String label = value.trim();
            if (!label.isEmpty()) normalized.add(label);
        }
        return new ArrayList<>(normalized);
    }
}
