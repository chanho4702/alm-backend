package com.platform.almbackend.admin.dto;

import com.platform.almbackend.domain.AuditLog;

import java.time.Instant;

public record AuditLogResponse(
        long id,
        String eventType,
        long actorId,
        Long projectId,
        String targetKey,
        String summary,
        Instant occurredAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getEventType(), log.getActorId(), log.getProjectId(),
                log.getTargetKey(), log.getSummary(), log.getOccurredAt());
    }
}
