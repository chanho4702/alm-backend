package com.platform.almbackend.history;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueChangeLog;
import com.platform.almbackend.history.dto.IssueChangeResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 변경 이력 기록·조회. 기록은 이슈/스프린트 서비스가 값이 실제로 바뀐 순간에만 호출한다 —
 * 같은 값 저장으로 줄이 쌓이면 번다운에 가짜 계단이 생긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueChangeLogService {
    private final IssueChangeLogRepository logs;
    private final ProjectService projectService;

    /** 이슈 생성 — 최초 상태와(있으면) 최초 스프린트 편입을 남긴다. */
    public void recordCreated(long actorId, Issue issue) {
        Instant now = Instant.now();
        logs.save(IssueChangeLog.of(issue, issue.getSprintId(), ChangeField.STATUS,
                null, issue.getStatus(), actorId, now));
        if (issue.getSprintId() != null) {
            logs.save(IssueChangeLog.of(issue, issue.getSprintId(), ChangeField.SPRINT,
                    null, String.valueOf(issue.getSprintId()), actorId, now));
        }
    }

    /** 상태·스프린트 변경 — 바뀐 것만 남긴다. */
    public void recordChanges(
            long actorId, Issue issue, String previousStatus, Long previousSprintId) {
        Instant now = Instant.now();
        if (!Objects.equals(previousStatus, issue.getStatus())) {
            logs.save(IssueChangeLog.of(issue, issue.getSprintId(), ChangeField.STATUS,
                    previousStatus, issue.getStatus(), actorId, now));
        }
        if (!Objects.equals(previousSprintId, issue.getSprintId())) {
            logs.save(IssueChangeLog.of(issue, issue.getSprintId(), ChangeField.SPRINT,
                    text(previousSprintId), text(issue.getSprintId()), actorId, now));
        }
    }

    @Transactional(readOnly = true)
    public List<IssueChangeResponse> history(
            long userId, long projectId, ChangeField field, Long sprintId, Instant since) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        return logs.findHistory(projectId, field, sprintId, text(sprintId), since).stream()
                .map(IssueChangeResponse::from)
                .toList();
    }

    private static String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
