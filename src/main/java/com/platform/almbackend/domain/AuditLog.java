package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 감사 로그 한 줄 — 추가만 하고 고치지 않는다. 온프렘 규정 대응(누가·무엇을·언제)의 원천. */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 48, updatable = false)
    private String eventType;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "project_id", updatable = false)
    private Long projectId;

    @Column(name = "target_key", length = 80, updatable = false)
    private String targetKey;

    @Column(length = 300, updatable = false)
    private String summary;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static AuditLog of(
            String eventId, String eventType, long actorId, Long projectId,
            String targetKey, String summary, Instant occurredAt) {
        AuditLog log = new AuditLog();
        log.eventId = eventId;
        log.eventType = eventType;
        log.actorId = actorId;
        log.projectId = projectId;
        log.targetKey = targetKey;
        log.summary = summary == null ? null : summary.substring(0, Math.min(summary.length(), 300));
        log.occurredAt = occurredAt;
        return log;
    }
}
