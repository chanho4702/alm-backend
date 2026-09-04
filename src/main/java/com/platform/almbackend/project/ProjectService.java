package com.platform.almbackend.project;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.board.BoardService;
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
import org.springframework.transaction.annotation.Propagation;
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
    /** 설정 서비스는 이 서비스를 쓴다(권한) — 순환을 끊으려고 지연 주입 */
    private final ObjectProvider<SchemeService> schemeService;
    private final ObjectProvider<BoardService> boardService;
    private final EventRelay events;
    private final TrashPolicy trashPolicy;

    @Transactional(readOnly = true)
    public List<ProjectResponse> listAccessible(long userId) {
        AccessScope scope = permissions.accessibleProjects(userId);
        List<Project> values = scope.all()
                ? projects.findAllByOrderByNameAsc()
                : projects.findAllByIdInOrderByNameAsc(scope.projectIds());
        return values.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(long userId, long projectId) {
        Project project = requireProject(projectId);
        require(userId, projectId, AlmAction.VIEW);
        return toResponse(project);
    }

    public ProjectResponse create(long userId, ProjectCreateRequest request) {
        String key = request.key().trim().toUpperCase(Locale.ROOT);
        if (projects.existsByKey(key)) {
            throw new ConflictException("이미 존재하는 프로젝트 키입니다: " + key);
        }
        Project fresh = Project.of(key, request.name().trim(), normalizeDescription(request.description()));
        fresh.assignLead(userId);
        Project saved = projects.save(fresh);
        // wiki와 같은 계약: grant 실패가 정본 생성을 롤백시키지는 않는다. 운영자는 grants REST로 복구한다.
        permissions.grantProjectAdmin(userId, saved.getId());
        schemeService.getObject().initProject(saved.getId());
        boardService.getObject().createDefault(saved.getId());
        events.afterCommit(AlmEvents.projectCreated(userId, saved));
        return toResponse(saved);
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
        if (request.defaultAssignee() != null
                && !Project.ASSIGNEE_UNASSIGNED.equals(request.defaultAssignee())
                && !Project.ASSIGNEE_LEAD.equals(request.defaultAssignee())) {
            throw new IllegalArgumentException("기본 담당자는 unassigned/lead 중 하나입니다");
        }
        project.editDetails(
                request.category() == null ? null : request.category().trim(),
                request.leadId(),
                Boolean.TRUE.equals(request.clearLead()),
                request.defaultAssignee(),
                request.icon() == null ? null : request.icon().trim(),
                request.color() == null ? null : request.color().trim(),
                request.url() == null ? null : request.url().trim());
        events.afterCommit(AlmEvents.projectUpdated(userId, project));
        return toResponse(project);
    }

    public void delete(long userId, long projectId) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        requireAdminIgnoringArchive(userId, projectId);
        // 삭제 = 휴지통 이동(지라). 복원·영구 삭제는 휴지통에서 한다. 검색 색인에서는 빠지도록 삭제 이벤트를 낸다
        project.trash(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
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
        if (action != AlmAction.VIEW && projects.findById(projectId).map(Project::isArchived).orElse(false)) {
            throw new ForbiddenException("보관된 프로젝트는 읽기만 할 수 있습니다");
        }
    }

    /** 보관 가드를 우회하는 관리자 확인 — 보관 해제·휴지통 이동에 쓴다 */
    private void requireAdminIgnoringArchive(long userId, long projectId) {
        if (!permissions.isAllowed(userId, projectId, AlmAction.ADMIN)) {
            throw new ForbiddenException("ADMIN 권한이 필요합니다 (project " + projectId + ")");
        }
    }

    // ── 보관 · 휴지통 ──

    public ProjectResponse archive(long userId, long projectId) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        requireAdminIgnoringArchive(userId, projectId);
        project.archive(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        events.afterCommit(AlmEvents.projectUpdated(userId, project));
        return toResponse(project);
    }

    public ProjectResponse unarchive(long userId, long projectId) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        requireAdminIgnoringArchive(userId, projectId);
        project.unarchive();
        events.afterCommit(AlmEvents.projectUpdated(userId, project));
        return toResponse(project);
    }

    /** 휴지통 목록 — 접근 가능한 것만 */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listTrash(long userId) {
        AccessScope scope = permissions.accessibleProjects(userId);
        return projects.findTrashed().stream()
                .filter(p -> scope.all() || scope.projectIds().contains(p.getId()))
                .map(this::toResponse).toList();
    }

    public ProjectResponse restoreFromTrash(long userId, long projectId) {
        Project project = projects.findTrashedById(projectId)
                .orElseThrow(() -> new NotFoundException("휴지통에 없는 프로젝트입니다: " + projectId));
        requireAdminIgnoringArchive(userId, projectId);
        project.restoreFromTrash();
        events.afterCommit(AlmEvents.projectCreated(userId, project));
        return toResponse(project);
    }

    /** 영구 삭제 — 휴지통에 있는 프로젝트만. 보관된 이슈까지 함께 지운다 */
    public void purge(long userId, long projectId) {
        Project project = projects.findTrashedById(projectId)
                .orElseThrow(() -> new NotFoundException("휴지통에 없는 프로젝트입니다: " + projectId));
        requireAdminIgnoringArchive(userId, projectId);
        purgeInternal(project.getId(), userId);
    }

    /**
     * 보존 기간이 지난 자동 비우기({@link TrashPurgeJob}) 전용 — 요청 주체가 없으므로 권한 검사를 하지
     * 않는다. 대상 선정은 이미 "휴지통에 있고 보존 기간이 지났다"로 끝났고, 사람의 판단이 끼어들 여지가
     * 없다. 프로젝트마다 트랜잭션을 새로 열어 한 건의 실패가 나머지를 막지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeExpired(long projectId, java.time.Instant threshold) {
        // 임계값을 다시 본다 — 선정 뒤 복원·재삭제된 프로젝트는 보존 기간이 새로 시작됐다
        Project project = projects.findTrashedByIdDeletedBefore(projectId, threshold)
                .orElseThrow(() -> new NotFoundException("보존 기간이 지난 휴지통 프로젝트가 아닙니다: " + projectId));
        // 지운 주체가 없는 이벤트다 — 삭제를 요청했던 사용자는 이미 알 수 없다
        purgeInternal(project.getId(), SYSTEM_ACTOR);
    }

    /** purge 순서는 한 곳에만 둔다 — 첨부 오브젝트 → 이슈 부속 → 이슈 → 버전 → 스프린트 → 프로젝트 → grant */
    private void purgeInternal(long projectId, long actorId) {
        attachmentService.getObject().deleteAllForProject(projectId);
        issues.purgeLabelsByProjectId(projectId);
        issues.purgeComponentsByProjectId(projectId);
        issues.purgeByProjectId(projectId);
        versions.deleteByProjectId(projectId);
        sprints.deleteByProjectId(projectId);
        projects.purgeTrashedById(projectId);
        permissions.revokeProjectGrants(projectId);
        events.afterCommit(AlmEvents.projectDeleted(actorId, projectId));
    }

    /** 휴지통 자동 비우기처럼 사람이 아닌 주체가 낸 이벤트의 actor */
    static final long SYSTEM_ACTOR = 0L;

    private ProjectResponse toResponse(Project project) {
        return ProjectResponse.from(project, trashPolicy.purgeAt(project.getDeletedAt()));
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }
}

