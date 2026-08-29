package com.platform.almbackend.version;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.ProjectVersion;
import com.platform.almbackend.domain.VersionStatus;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectVersionRepository;
import com.platform.almbackend.version.dto.VersionCreateRequest;
import com.platform.almbackend.version.dto.VersionReleaseRequest;
import com.platform.almbackend.version.dto.VersionResponse;
import com.platform.almbackend.version.dto.VersionUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 버전 수명주기. 이슈의 수정 버전 소속 검증(`requireAssignable`)은 IssueService가 부른다.
 * 완료 판정은 프론트 소유라 릴리스의 "미완료" 기준은 요청이 준 상태 목록이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VersionService {
    private final ProjectVersionRepository versions;
    private final IssueRepository issues;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<VersionResponse> list(long userId, long projectId) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        return versions.findByProjectIdOrderByCreatedAtAscIdAsc(projectId).stream()
                .map(VersionResponse::from)
                .toList();
    }

    public VersionResponse create(long userId, long projectId, VersionCreateRequest request) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.EDIT);
        String name = request.name().trim();
        if (versions.existsByProjectIdAndName(projectId, name)) {
            throw new ConflictException("이미 있는 버전 이름입니다: " + name);
        }
        ProjectVersion created = ProjectVersion.of(projectId, name, blankToNull(request.description()),
                request.startDate(), request.releaseDate());
        return VersionResponse.from(versions.save(created));
    }

    public VersionResponse update(long userId, long versionId, VersionUpdateRequest request) {
        long projectId = requireProjectId(versionId);
        projectService.require(userId, projectId, AlmAction.EDIT);
        ProjectVersion locked = lock(versionId);
        if (!locked.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 버전을 수정했습니다. 현재 "
                    + locked.getVersion() + ", 요청 " + request.expectedVersion());
        }
        String name = request.name().trim();
        if (!name.equals(locked.getName()) && versions.existsByProjectIdAndName(projectId, name)) {
            throw new ConflictException("이미 있는 버전 이름입니다: " + name);
        }
        locked.edit(name, blankToNull(request.description()), request.startDate(), request.releaseDate());
        return VersionResponse.from(locked);
    }

    /** 릴리스. 미완료 이슈는 지정한 버전으로 옮기고(같은 프로젝트·미릴리스만) 지정이 없으면 그대로 둔다. */
    public VersionResponse release(long userId, long versionId, VersionReleaseRequest request) {
        long projectId = requireProjectId(versionId);
        projectService.require(userId, projectId, AlmAction.EDIT);
        ProjectVersion locked = lock(versionId);
        if (locked.getStatus() == VersionStatus.RELEASED) {
            throw new ConflictException("이미 릴리스된 버전입니다");
        }
        if (locked.getStatus() == VersionStatus.ARCHIVED) {
            throw new ConflictException("보관된 버전은 릴리스할 수 없습니다");
        }
        Long targetId = request == null ? null : request.moveUnresolvedToVersionId();
        if (targetId != null) {
            if (targetId == versionId) {
                throw new IllegalArgumentException("릴리스하는 버전으로는 이관할 수 없습니다");
            }
            ProjectVersion target = versions.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("버전을 찾을 수 없습니다: " + targetId));
            if (!Objects.equals(target.getProjectId(), projectId)) {
                throw new IllegalArgumentException("다른 프로젝트의 버전입니다: " + targetId);
            }
            if (target.getStatus() != VersionStatus.UNRELEASED) {
                throw new IllegalArgumentException(target.getStatus() == VersionStatus.RELEASED
                        ? "릴리스된 버전으로는 이관할 수 없습니다"
                        : "보관된 버전으로는 이관할 수 없습니다");
            }
            Set<String> done = request.doneStatuses() == null ? Set.of() : Set.copyOf(request.doneStatuses());
            for (Issue issue : issues.findByFixVersionId(versionId)) {
                if (!done.contains(issue.getStatus())) {
                    issue.assignFixVersion(targetId);
                }
            }
        }
        locked.release(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return VersionResponse.from(locked);
    }

    public VersionResponse archive(long userId, long versionId) {
        long projectId = requireProjectId(versionId);
        projectService.require(userId, projectId, AlmAction.EDIT);
        ProjectVersion locked = lock(versionId);
        try {
            locked.archive();
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return VersionResponse.from(locked);
    }

    /** 삭제하면 달려 있던 이슈의 수정 버전을 비운다 — DB SET NULL에만 기대지 않고 명시한다. */
    public void delete(long userId, long versionId) {
        long projectId = requireProjectId(versionId);
        projectService.require(userId, projectId, AlmAction.EDIT);
        ProjectVersion locked = lock(versionId);
        issues.clearFixVersion(versionId);
        versions.delete(locked);
    }

    /** 이슈에 달 수 있는 버전인지 — 같은 프로젝트이고 보관되지 않았어야 한다. */
    @Transactional(readOnly = true)
    public void requireAssignable(long versionId, long projectId) {
        ProjectVersion version = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("버전을 찾을 수 없습니다: " + versionId));
        if (!Objects.equals(version.getProjectId(), projectId)) {
            throw new IllegalArgumentException("다른 프로젝트의 버전입니다: " + versionId);
        }
        if (version.getStatus() == VersionStatus.ARCHIVED) {
            throw new IllegalArgumentException("보관된 버전에는 이슈를 달 수 없습니다");
        }
    }

    private long requireProjectId(long versionId) {
        return versions.findProjectIdById(versionId)
                .orElseThrow(() -> new NotFoundException("버전을 찾을 수 없습니다: " + versionId));
    }

    private ProjectVersion lock(long versionId) {
        return versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new NotFoundException("버전을 찾을 수 없습니다: " + versionId));
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
