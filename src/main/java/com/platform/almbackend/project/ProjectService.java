package com.platform.almbackend.project;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.ForbiddenException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.project.dto.ProjectCreateRequest;
import com.platform.almbackend.project.dto.ProjectResponse;
import com.platform.almbackend.project.dto.ProjectUpdateRequest;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectVersionRepository;
import com.platform.almbackend.attachment.AttachmentService;
import org.springframework.beans.factory.ObjectProvider;
import com.platform.almbackend.repository.SprintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {
    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final SprintRepository sprints;
    private final ProjectVersionRepository versions;
    // AttachmentService → ProjectService → AttachmentService 순환을 끊는다 — 삭제 연쇄 때만 늦게 받는다
    private final ObjectProvider<AttachmentService> attachmentService;
    private final PermissionClient permissions;
    private final EventRelay events;

    @Transactional(readOnly = true)
    public List<ProjectResponse> listAccessible(long userId) {
        AccessScope scope = permissions.accessibleProjects(userId);
        List<Project> values = scope.all()
                ? projects.findAllByOrderByNameAsc()
                : projects.findAllByIdInOrderByNameAsc(scope.projectIds());
        return values.stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(long userId, long projectId) {
        Project project = requireProject(projectId);
        require(userId, projectId, AlmAction.VIEW);
        return ProjectResponse.from(project);
    }

    public ProjectResponse create(long userId, ProjectCreateRequest request) {
        String key = request.key().trim().toUpperCase(Locale.ROOT);
        if (projects.existsByKey(key)) {
            throw new ConflictException("이미 존재하는 프로젝트 키입니다: " + key);
        }
        Project saved = projects.save(Project.of(
                key, request.name().trim(), normalizeDescription(request.description())));
        // wiki와 같은 계약: grant 실패가 정본 생성을 롤백시키지는 않는다. 운영자는 grants REST로 복구한다.
        permissions.grantProjectAdmin(userId, saved.getId());
        events.afterCommit(AlmEvents.projectCreated(userId, saved));
        return ProjectResponse.from(saved);
    }

    public ProjectResponse update(long userId, long projectId, ProjectUpdateRequest request) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        require(userId, projectId, AlmAction.ADMIN);
        if (!project.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 프로젝트를 수정했습니다. 현재 "
                    + project.getVersion() + ", 요청 " + request.expectedVersion());
        }
        project.edit(request.name().trim(), normalizeDescription(request.description()));
        events.afterCommit(AlmEvents.projectUpdated(userId, project));
        return ProjectResponse.from(project);
    }

    public void delete(long userId, long projectId) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        require(userId, projectId, AlmAction.ADMIN);
        // 이슈가 스프린트를 참조하므로 순서가 있다. DB cascade에 기대지 않고 여기서 명시한다.
        attachmentService.getObject().deleteAllForProject(projectId);
        issues.deleteByProjectId(projectId);
        versions.deleteByProjectId(projectId);
        sprints.deleteByProjectId(projectId);
        projects.delete(project);
        permissions.revokeProjectGrants(projectId);
        events.afterCommit(AlmEvents.projectDeleted(userId, projectId));
    }

    @Transactional(readOnly = true)
    public Project requireProject(long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
    }

    public void require(long userId, long projectId, AlmAction action) {
        if (!permissions.isAllowed(userId, projectId, action)) {
            throw new ForbiddenException(action + " 권한이 필요합니다 (project " + projectId + ")");
        }
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }
}

