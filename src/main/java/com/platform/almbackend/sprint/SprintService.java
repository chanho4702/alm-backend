package com.platform.almbackend.sprint;

import com.platform.almbackend.common.ConflictException;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.sprint.dto.SprintCompleteRequest;
import com.platform.almbackend.sprint.dto.SprintCreateRequest;
import com.platform.almbackend.sprint.dto.SprintResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 스프린트 수명주기. 상태 전이와 이슈 재배치를 한 트랜잭션에서 끝내고, 프로젝트 행을 잠가
 * 같은 프로젝트의 다른 스프린트 조작·이슈 재정렬과 직렬화한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SprintService {
    private final SprintRepository sprints;
    private final IssueRepository issues;
    private final ProjectRepository projects;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<SprintResponse> list(long userId, long projectId) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        return sprints.findByProjectIdOrderBySprintNumberAsc(projectId).stream()
                .map(SprintResponse::from)
                .toList();
    }

    public SprintResponse create(long userId, long projectId, SprintCreateRequest request) {
        projectService.require(userId, projectId, AlmAction.EDIT);
        lockProject(projectId);
        long number = sprints.findMaxSprintNumberByProjectId(projectId) + 1;
        String name = request == null || request.name() == null || request.name().isBlank()
                ? "Sprint " + number
                : request.name().trim();
        return SprintResponse.from(sprints.save(Sprint.of(projectId, number, name)));
    }

    /**
     * 한 프로젝트에서 진행 중인 스프린트는 하나뿐이다. 애플리케이션 검사와 별개로 DB 부분 unique
     * 인덱스가 최종 판정을 하므로, 잠금 밖에서 밀려든 동시 요청도 409로 끝난다.
     */
    public SprintResponse start(long userId, long sprintId) {
        Sprint sprint = requireSprint(sprintId);
        projectService.require(userId, sprint.getProjectId(), AlmAction.EDIT);
        lockProject(sprint.getProjectId());
        Sprint locked = lockSprint(sprintId);
        if (locked.getState() != SprintState.PLANNED) {
            throw new ConflictException("계획 상태의 스프린트만 시작할 수 있습니다: " + locked.getState());
        }
        if (sprints.findByProjectIdAndState(locked.getProjectId(), SprintState.ACTIVE).isPresent()) {
            throw new ConflictException("이미 진행 중인 스프린트가 있습니다");
        }
        locked.start(Instant.now());
        try {
            sprints.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 진행 중인 스프린트가 있습니다");
        }
        return SprintResponse.from(locked);
    }

    /**
     * 완료하면 미완료 이슈는 백로그 맨 뒤로 되돌린다. 완료 판정 기준은 요청이 알려준 상태 목록이며,
     * 목록이 비어 있으면 스프린트의 모든 이슈가 백로그로 돌아간다.
     */
    public SprintResponse complete(long userId, long sprintId, SprintCompleteRequest request) {
        Sprint sprint = requireSprint(sprintId);
        projectService.require(userId, sprint.getProjectId(), AlmAction.EDIT);
        lockProject(sprint.getProjectId());
        Sprint locked = lockSprint(sprintId);
        if (locked.getState() != SprintState.ACTIVE) {
            throw new ConflictException("진행 중인 스프린트만 완료할 수 있습니다: " + locked.getState());
        }
        Set<String> doneStatuses = request == null || request.doneStatuses() == null
                ? Set.of()
                : Set.copyOf(request.doneStatuses());
        long backlogOrder = issues.findMaxSortOrderInRankGroup(locked.getProjectId(), null);
        List<Issue> retained = new ArrayList<>();
        for (Issue issue : issues.findRankGroup(locked.getProjectId(), sprintId)) {
            if (doneStatuses.contains(issue.getStatus())) {
                retained.add(issue);
            } else {
                issue.moveToBacklog(++backlogOrder);
            }
        }
        // 남은 이슈 사이에 구멍이 생기므로 스프린트 그룹도 다시 조밀하게 만든다.
        for (int i = 0; i < retained.size(); i++) {
            retained.get(i).resequence(i + 1L);
        }
        locked.complete(Instant.now());
        return SprintResponse.from(locked);
    }

    @Transactional(readOnly = true)
    public Sprint requireSprint(long sprintId) {
        return sprints.findById(sprintId)
                .orElseThrow(() -> new NotFoundException("스프린트를 찾을 수 없습니다: " + sprintId));
    }

    /** 이슈 랭크 이동이 대상 스프린트를 검증할 때 쓴다 — 다른 프로젝트 스프린트로는 옮길 수 없다. */
    @Transactional(readOnly = true)
    public Sprint requireSprintInProject(long sprintId, long projectId) {
        Sprint sprint = requireSprint(sprintId);
        if (!sprint.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("다른 프로젝트의 스프린트입니다: " + sprintId);
        }
        return sprint;
    }

    private Sprint lockSprint(long sprintId) {
        return sprints.findByIdForUpdate(sprintId)
                .orElseThrow(() -> new NotFoundException("스프린트를 찾을 수 없습니다: " + sprintId));
    }

    private Project lockProject(long projectId) {
        return projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
    }
}
