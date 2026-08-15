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
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        return issues.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
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
        Issue issue = issues.save(Issue.of(
                projectId,
                number,
                project.getKey() + "-" + number,
                request.title().trim(),
                normalizeDescription(request.description()),
                request.type() == null ? IssueType.TASK : request.type(),
                normalizeStatus(request.status()),
                request.priority() == null ? IssuePriority.MEDIUM : request.priority(),
                request.assigneeId(),
                userId));
        events.afterCommit(AlmEvents.issueCreated(userId, issue));
        return IssueResponse.from(issue);
    }

    public IssueResponse update(long userId, long issueId, IssueUpdateRequest request) {
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        projectService.require(userId, issue.getProjectId(), AlmAction.EDIT);
        if (!issue.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 이슈를 수정했습니다. 현재 "
                    + issue.getVersion() + ", 요청 " + request.expectedVersion());
        }
        issue.edit(
                request.title().trim(),
                normalizeDescription(request.description()),
                request.type(),
                normalizeStatus(request.status()),
                request.priority(),
                request.assigneeId());
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        return IssueResponse.from(issue);
    }

    public void delete(long userId, long issueId) {
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        projectService.require(userId, issue.getProjectId(), AlmAction.EDIT);
        events.afterCommit(AlmEvents.issueDeleted(userId, issue));
        issues.delete(issue);
    }

    private Issue requireIssue(long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "todo" : value.trim();
        if (status.isEmpty()) throw new IllegalArgumentException("상태 ID가 필요합니다");
        return status;
    }
}

