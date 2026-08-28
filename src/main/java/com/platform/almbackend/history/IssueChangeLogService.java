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
import java.time.temporal.ChronoUnit;
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
        Instant now = now();
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
        recordChanges(actorId, issue, previousStatus, previousSprintId, now());
    }

    /**
     * 시각을 호출자가 정하는 형태. 스프린트 완료처럼 **한 트랜잭션이 여러 이슈를 한꺼번에 옮기는**
     * 경우에 쓴다 — 이력의 시각이 스프린트 `completedAt`과 같아야 리포트가 "완료 처리로 옮긴 것"과
     * "사람이 도중에 뺀 것"을 구분할 수 있다(프론트 reportMetrics의 식별 규칙).
     */
    public void recordChanges(
            long actorId, Issue issue, String previousStatus, Long previousSprintId, Instant now) {
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

    /** DB(timestamptz)가 담는 마이크로초까지만 — 메모리 값과 조회 값이 같아야 리포트 식별이 성립한다 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
