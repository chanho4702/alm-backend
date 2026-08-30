package com.platform.almbackend.issue;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.collab.CollaborationService;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueImportRequest;
import com.platform.almbackend.issue.dto.IssueImportResponse;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueRankRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.sprint.SprintService;
import com.platform.almbackend.version.VersionService;
import com.platform.almbackend.attachment.AttachmentService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;
import java.util.Locale;
import java.util.HashSet;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class IssueService {
    private final IssueRepository issues;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final SprintService sprintService;
    private final VersionService versionService;
    private final AttachmentService attachmentService;
    private final EventRelay events;
    private final IssueChangeLogService changeLog;
    private final NotificationService notifications;
    private final SchemeService settings;
    private final CollaborationService collaboration;

    @Transactional(readOnly = true)
    public List<IssueResponse> list(long userId, long projectId) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        return issues.findByProjectIdOrderBySortOrderAscKeyAsc(projectId).stream()
                .map(IssueResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse get(long userId, long issueId) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return IssueResponse.from(issue);
    }

    public IssueResponse create(long userId, long projectId, IssueCreateRequest request) {
        projectService.require(userId, projectId, AlmAction.EDIT);
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        return createNumbered(userId, project, project.nextIssueNumber(), request);
    }

    /**
     * 이관·CSV 가져오기 — 항목마다 만들고 실패는 사유와 함께 분리한다(전부 롤백 없음). 키가 있으면
     * 보존하고 카운터를 그 번호 이상으로 앞당긴다. 검증(형식·중복·제목)은 저장 전에 끝나므로 실패한
     * 항목이 트랜잭션을 더럽히지 않는다.
     */
    public IssueImportResponse importIssues(long userId, long projectId, IssueImportRequest request) {
        projectService.require(userId, projectId, AlmAction.EDIT);
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        Pattern keyPattern = Pattern.compile("^" + Pattern.quote(project.getKey()) + "-(\\d+)$");
        int created = 0;
        List<IssueImportResponse.Failure> failed = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            IssueImportRequest.Item item = request.items().get(i);
            String label = item.key() != null && !item.key().isBlank() ? item.key() : String.valueOf(item.title());
            try {
                if (item.title() == null || item.title().isBlank()) {
                    throw new IllegalArgumentException("이슈 제목을 입력하세요");
                }
                long number;
                if (item.key() != null && !item.key().isBlank()) {
                    String key = item.key().trim().toUpperCase(Locale.ROOT);
                    Matcher matcher = keyPattern.matcher(key);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("키는 " + project.getKey() + "-번호 형식이어야 합니다: " + item.key());
                    }
                    if (!seenKeys.add(key) || issues.findByKey(key).isPresent()) {
                        throw new IllegalArgumentException("이미 있는 키입니다: " + key);
                    }
                    number = Long.parseLong(matcher.group(1));
                    project.reserveIssueNumber(number);
                } else {
                    number = project.nextIssueNumber();
                }
                createNumbered(userId, project, number, item.toCreate());
                created++;
            } catch (RuntimeException e) {
                failed.add(new IssueImportResponse.Failure(i + 1, label, e.getMessage()));
            }
        }
        return new IssueImportResponse(created, failed);
    }

    private IssueResponse createNumbered(long userId, Project project, long number, IssueCreateRequest request) {
        long projectId = project.getId();
        String type = normalizeType(request.type() == null ? settings.defaultType(projectId) : request.type());
        settings.assertTypeEnabled(projectId, type);
        IssueDetailsRequest details = request.details();
        Long parentId = details == null ? null : details.parentId();
        validateParent(projectId, null, type, parentId);
        Long sprintId = details == null ? null : details.sprintId();
        if (sprintId != null) sprintService.requireSprintInProject(sprintId, projectId);
        long order = issues.findMaxSortOrderInRankGroup(projectId, sprintId) + 1;
        Issue issue = issues.save(Issue.of(
                projectId,
                number,
                project.getKey() + "-" + number,
                request.title().trim(),
                normalizeDescription(request.description()),
                type,
                requireStatus(projectId, request.status()),
                settings.resolvePriority(projectId, request.priority()),
                request.assigneeId() == null ? project.resolveDefaultAssignee() : request.assigneeId(),
                userId,
                parentId,
                sprintId,
                details == null ? null : details.dueDate(),
                details == null ? null : details.estimateHours(),
                normalizeLabels(details == null ? null : details.labels()),
                order));
        changeLog.recordCreated(userId, issue);
        collaboration.recordCreated(userId, issue);
        notifications.onIssueCreated(userId, issue);
        events.afterCommit(AlmEvents.issueCreated(userId, issue));
        return IssueResponse.from(issue);
    }

    public IssueResponse update(long userId, long issueId, IssueUpdateRequest request) {
        Issue snapshot = requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        lockProject(snapshot.getProjectId());
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        if (!issue.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 이슈를 수정했습니다. 현재 "
                    + issue.getVersion() + ", 요청 " + request.expectedVersion());
        }
        String nextType = normalizeType(request.type() == null ? issue.getType() : request.type());
        if (!nextType.equals(issue.getType())) settings.assertTypeEnabled(issue.getProjectId(), nextType);
        validateChildren(issue.getId(), nextType);
        IssueDetailsRequest details = request.details();
        Long requestedParentId = details == null ? issue.getParentId() : details.parentId();
        Long parentId = resolveParent(issue, nextType, requestedParentId);
        Long sprintId = details == null ? issue.getSprintId() : details.sprintId();
        if (sprintId != null && !Objects.equals(sprintId, issue.getSprintId())) {
            sprintService.requireSprintInProject(sprintId, issue.getProjectId());
        }
        LocalDate dueDate = details == null ? issue.getDueDate() : details.dueDate();
        BigDecimal estimateHours = details == null ? issue.getEstimateHours() : details.estimateHours();
        IssueResolution resolution = details == null ? issue.getResolution() : details.resolution();
        Long fixVersionId = details == null ? issue.getFixVersionId() : details.fixVersionId();
        if (fixVersionId != null && !Objects.equals(fixVersionId, issue.getFixVersionId())) {
            versionService.requireAssignable(fixVersionId, issue.getProjectId());
        }
        List<String> labels = details == null
                ? List.copyOf(issue.getLabels())
                : normalizeLabels(details.labels());
        String status = request.status() == null ? issue.getStatus() : requireStatus(issue.getProjectId(), request.status());
        settings.assertTransitionAllowed(issue.getProjectId(), issue.getStatus(), status);
        // 상태나 스프린트가 바뀌면 대상 컬럼 맨 뒤로 보낸다. 정밀 배치는 move/rank가 한다.
        Long previousSprintId = issue.getSprintId();
        String previousStatus = issue.getStatus();
        Long previousAssigneeId = issue.getAssigneeId();
        CollaborationService.Snapshot snapshotBefore = CollaborationService.Snapshot.of(issue);
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
        if (regrouped) {
            List<Issue> source = Objects.equals(previousSprintId, sprintId)
                    ? List.of()
                    : rankGroupWithout(issue, previousSprintId);
            List<Issue> group = rankGroupWithout(issue, sprintId);
            group.add(afterLastOfStatus(group, status), issue);
            resequence(group);
            resequence(source);
        }
        changeLog.recordChanges(userId, issue, previousStatus, previousSprintId);
        collaboration.recordUpdate(userId, snapshotBefore, issue);
        notifications.onIssueUpdated(userId, issue, previousStatus, previousAssigneeId);
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        return IssueResponse.from(issue);
    }

    public void delete(long userId, long issueId) {
        Issue snapshot = requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        lockProject(snapshot.getProjectId());
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        events.afterCommit(AlmEvents.issueDeleted(userId, issue));
        issues.clearParentByParentId(issueId);
        attachmentService.deleteAllForIssue(issueId);
        issues.delete(issue);
    }

    /**
     * 보드 컬럼 이동. `sortOrder`는 랭크 그룹(프로젝트+스프린트) 하나에만 존재하는 단일 순서열이고
     * 보드 컬럼은 그 순서열을 상태로 거른 결과다 — 컬럼마다 따로 1부터 매기면 같은 그룹 안에서
     * 번호가 충돌한다. 그래서 컬럼 기준으로 자리를 찾은 뒤 그룹 전체를 다시 매긴다.
     */
    public IssueResponse move(long userId, long issueId, IssueMoveRequest request) {
        Issue snapshot = requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        lockProject(snapshot.getProjectId());
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        String status = requireStatus(issue.getProjectId(), request.status());
        settings.assertTransitionAllowed(issue.getProjectId(), issue.getStatus(), status);
        String previousStatus = issue.getStatus();
        CollaborationService.Snapshot moveSnapshot = CollaborationService.Snapshot.of(issue);
        issue.moveTo(status, issue.getSortOrder());
        List<Issue> group = rankGroupWithout(issue, issue.getSprintId());
        int insertAt = indexOfInColumn(group, request.beforeId(), status);
        if (insertAt < 0) insertAt = afterLastOfStatus(group, status);
        group.add(insertAt, issue);
        resequence(group);
        changeLog.recordChanges(userId, issue, previousStatus, issue.getSprintId());
        collaboration.recordUpdate(userId, moveSnapshot, issue);
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        return IssueResponse.from(issue);
    }

    /**
     * 백로그/스프린트 랭크 이동 — 대상 그룹(프로젝트+스프린트, 상태 무관) 안에서 자리를 잡는다.
     * 요청 본문이 없으면 백로그 맨 뒤로 본다.
     */
    public IssueResponse rank(long userId, long issueId, IssueRankRequest request) {
        Issue snapshot = requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        lockProject(snapshot.getProjectId());
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        Long sprintId = request == null ? null : request.sprintId();
        if (sprintId != null) sprintService.requireSprintInProject(sprintId, issue.getProjectId());
        Long previousSprintId = issue.getSprintId();
        List<Issue> source = Objects.equals(previousSprintId, sprintId)
                ? List.of()
                : rankGroupWithout(issue, previousSprintId);
        issue.rankTo(sprintId, issue.getSortOrder());
        List<Issue> group = rankGroupWithout(issue, sprintId);
        int insertAt = indexOf(group, request == null ? null : request.beforeId());
        if (insertAt < 0) insertAt = group.size();
        group.add(insertAt, issue);
        resequence(group);
        // 떠난 그룹도 다시 조밀하게 만든다 — 그룹이 항상 1..n이면 이후 삽입 위치 계산이 단순하다.
        resequence(source);
        changeLog.recordChanges(userId, issue, issue.getStatus(), previousSprintId);
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        return IssueResponse.from(issue);
    }

    private List<Issue> rankGroupWithout(Issue issue, Long sprintId) {
        List<Issue> group = new ArrayList<>(issues.findRankGroup(issue.getProjectId(), sprintId));
        group.removeIf(entry -> entry.getId().equals(issue.getId()));
        return group;
    }

    /**
     * beforeId는 대상 컬럼 안의 이슈여야 한다. 다른 컬럼의 이슈거나 이미 사라졌으면 -1이다 —
     * 드래그 도중 다른 사용자가 그 이슈를 옮겼을 수 있고, 화면은 이동 후 항상 재조회한다.
     */
    private static int indexOfInColumn(List<Issue> group, Long beforeId, String status) {
        if (beforeId == null) return -1;
        for (int i = 0; i < group.size(); i++) {
            Issue entry = group.get(i);
            if (entry.getId().equals(beforeId) && Objects.equals(entry.getStatus(), status)) return i;
        }
        return -1;
    }

    private static int indexOf(List<Issue> group, Long beforeId) {
        if (beforeId == null) return -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i).getId().equals(beforeId)) return i;
        }
        return -1;
    }

    /** 대상 컬럼의 마지막 다음 자리. 컬럼이 비었으면 그룹 맨 뒤다. */
    private static int afterLastOfStatus(List<Issue> group, String status) {
        int last = -1;
        for (int i = 0; i < group.size(); i++) {
            if (Objects.equals(group.get(i).getStatus(), status)) last = i;
        }
        return last < 0 ? group.size() : last + 1;
    }

    private static void resequence(List<Issue> group) {
        for (int i = 0; i < group.size(); i++) {
            group.get(i).resequence(i + 1L);
        }
    }

    private Issue requireIssue(long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    private Project lockProject(long projectId) {
        return projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    /** 상태 id는 프로젝트 워크플로에 있어야 한다. 비면 워크플로의 첫 '할 일' 상태 */
    private String requireStatus(long projectId, String value) {
        String status = value == null || value.isBlank() ? settings.defaultStatus(projectId) : value.trim();
        settings.assertValidStatus(projectId, status);
        return status;
    }

    /** 옛 클라이언트의 대문자 enum 이름(TASK)도 레지스트리 id(task)로 받는다 */
    private static String normalizeType(String value) {
        return value == null ? "task" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Long resolveParent(Issue issue, String newType, Long requestedParentId) {
        if (requestedParentId == null) return null;
        Issue parent = requireParent(requestedParentId);
        boolean allowed = isParentAllowed(issue.getProjectId(), issue.getId(), newType, parent);
        if (allowed) return requestedParentId;
        if (!Objects.equals(issue.getType(), newType)
                && Objects.equals(issue.getParentId(), requestedParentId)) {
            // 기존 부모를 그대로 보낸 상태에서 타입만 바뀌어 관계가 깨지면 프론트 목업과 같이 자동 해제한다.
            return null;
        }
        throw new IllegalArgumentException("이슈 타입에 맞지 않는 부모입니다");
    }

    private void validateParent(long projectId, Long issueId, String type, Long parentId) {
        if (parentId == null) return;
        Issue parent = requireParent(parentId);
        if (!isParentAllowed(projectId, issueId, type, parent)) {
            throw new IllegalArgumentException("이슈 타입에 맞지 않는 부모입니다");
        }
    }

    private Issue requireParent(long parentId) {
        return issues.findById(parentId)
                .orElseThrow(() -> new NotFoundException("부모 이슈를 찾을 수 없습니다: " + parentId));
    }

    private boolean isParentAllowed(long projectId, Long issueId, String childType, Issue parent) {
        if (!Objects.equals(parent.getProjectId(), projectId)) return false;
        if (issueId != null && Objects.equals(parent.getId(), issueId)) return false;
        return hierarchyAllows(childType, parent.getType());
    }

    /** 계층은 타입 id가 아니라 레지스트리 level에서 — 상위(epic)는 부모 없음, 일반의 부모는 상위, 하위 작업의 부모는 일반 */
    private boolean hierarchyAllows(String childType, String parentType) {
        String child = settings.typeLevel(childType);
        String parent = settings.typeLevel(parentType);
        return switch (child) {
            case "epic" -> false;
            case "subtask" -> "standard".equals(parent);
            default -> "epic".equals(parent);
        };
    }

    private void validateChildren(long issueId, String newType) {
        for (Issue child : issues.findByParentId(issueId)) {
            if (!hierarchyAllows(child.getType(), newType)) {
                throw new IllegalArgumentException("하위 이슈가 있어 타입을 변경할 수 없습니다");
            }
        }
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
