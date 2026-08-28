package com.platform.almbackend.history.dto;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.domain.IssueChangeLog;

import java.time.Instant;

public record IssueChangeResponse(
        long id,
        long issueId,
        long projectId,
        Long sprintId,
        ChangeField field,
        String fromValue,
        String toValue,
        long actorId,
        Instant changedAt
) {
    public static IssueChangeResponse from(IssueChangeLog log) {
        return new IssueChangeResponse(log.getId(), log.getIssueId(), log.getProjectId(),
                log.getSprintId(), log.getField(), log.getFromValue(), log.getToValue(),
                log.getActorId(), log.getChangedAt());
    }
}
