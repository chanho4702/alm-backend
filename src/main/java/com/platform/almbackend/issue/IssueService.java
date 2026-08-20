package com.platform.almbackend.issue;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
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
    private final EventRelay events;

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
        long order = issues.findMaxSortOrderByProjectId(projectId) + 1;
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
                details == null ? null : details.dueDate(),
                details == null ? null : details.estimateHours(),
                normalizeLabels(details == null ? null : details.labels()),
                order));
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
        LocalDate dueDate = details == null ? issue.getDueDate() : details.dueDate();
        BigDecimal estimateHours = details == null ? issue.getEstimateHours() : details.estimateHours();
        List<String> labels = details == null
                ? List.copyOf(issue.getLabels())
                : normalizeLabels(details.labels());
        long order = issue.getSortOrder();
        issue.edit(
                request.title().trim(),
                normalizeDescription(request.description()),
                request.type(),
                normalizeStatus(request.status()),
                request.priority(),
                request.assigneeId(),
                parentId,
                dueDate,
                estimateHours,
                labels,
                order);
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
