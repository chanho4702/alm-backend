package com.platform.almbackend.issue;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.IssueType;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueRankRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.sprint.SprintService;
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
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class IssueService {
    private final IssueRepository issues;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final SprintService sprintService;
    private final EventRelay events;
    private final IssueChangeLogService changeLog;

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
        long number = project.nextIssueNumber();
        IssueType type = request.type() == null ? IssueType.TASK : request.type();
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
                normalizeStatus(request.status()),
                request.priority() == null ? IssuePriority.MEDIUM : request.priority(),
                request.assigneeId(),
                userId,
                parentId,
                sprintId,
                details == null ? null : details.dueDate(),
                details == null ? null : details.estimateHours(),
                normalizeLabels(details == null ? null : details.labels()),
                order));
        changeLog.recordCreated(userId, issue);
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
        validateChildren(issue.getId(), request.type());
        IssueDetailsRequest details = request.details();
        Long requestedParentId = details == null ? issue.getParentId() : details.parentId();
        Long parentId = resolveParent(issue, request.type(), requestedParentId);
        Long sprintId = details == null ? issue.getSprintId() : details.sprintId();
        if (sprintId != null && !Objects.equals(sprintId, issue.getSprintId())) {
            sprintService.requireSprintInProject(sprintId, issue.getProjectId());
        }
        LocalDate dueDate = details == null ? issue.getDueDate() : details.dueDate();
        BigDecimal estimateHours = details == null ? issue.getEstimateHours() : details.estimateHours();
        IssueResolution resolution = details == null ? issue.getResolution() : details.resolution();
        List<String> labels = details == null
                ? List.copyOf(issue.getLabels())
                : normalizeLabels(details.labels());
        String status = normalizeStatus(request.status());
        // 상태나 스프린트가 바뀌면 대상 컬럼 맨 뒤로 보낸다. 정밀 배치는 move/rank가 한다.
        Long previousSprintId = issue.getSprintId();
        String previousStatus = issue.getStatus();
        boolean regrouped = !Objects.equals(status, issue.getStatus())
                || !Objects.equals(sprintId, previousSprintId);
        long order = issue.getSortOrder();
        issue.edit(
                request.title().trim(),
                normalizeDescription(request.description()),
                request.type(),
                status,
                request.priority(),
                request.assigneeId(),
                parentId,
                sprintId,
                dueDate,
                estimateHours,
                resolution,
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
        String status = normalizeStatus(request.status());
        String previousStatus = issue.getStatus();
        issue.moveTo(status, issue.getSortOrder());
        List<Issue> group = rankGroupWithout(issue, issue.getSprintId());
        int insertAt = indexOfInColumn(group, request.beforeId(), status);
        if (insertAt < 0) insertAt = afterLastOfStatus(group, status);
        group.add(insertAt, issue);
        resequence(group);
        changeLog.recordChanges(userId, issue, previousStatus, issue.getSprintId());
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

    private static String normalizeStatus(String value) {
        String status = value == null ? "todo" : value.trim();
        if (status.isEmpty()) throw new IllegalArgumentException("상태 ID가 필요합니다");
        return status;
    }

    private Long resolveParent(Issue issue, IssueType newType, Long requestedParentId) {
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

    private void validateParent(long projectId, Long issueId, IssueType type, Long parentId) {
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

    private static boolean isParentAllowed(long projectId, Long issueId, IssueType childType, Issue parent) {
        if (!Objects.equals(parent.getProjectId(), projectId)) return false;
        if (issueId != null && Objects.equals(parent.getId(), issueId)) return false;
        return hierarchyAllows(childType, parent.getType());
    }

    private static boolean hierarchyAllows(IssueType childType, IssueType parentType) {
        return switch (childType) {
            case EPIC -> false;
            case SUBTASK -> parentType == IssueType.TASK
                    || parentType == IssueType.STORY
                    || parentType == IssueType.BUG;
            case TASK, STORY, BUG -> parentType == IssueType.EPIC;
        };
    }

    private void validateChildren(long issueId, IssueType newType) {
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
