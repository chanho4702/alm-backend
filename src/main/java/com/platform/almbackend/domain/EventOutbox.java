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

import java.time.Duration;
import java.time.Instant;

/**
 * 이벤트 아웃박스 한 줄(V26) — 변경과 같은 트랜잭션에 적히고, 커밋 뒤 워커가 Redis Streams로 내보낸다.
 * {@code payload}는 스트림에 싣는 EventEnvelope 직렬화 바이트 그대로라 eventId·occurredAt이 적은 시점 값으로 고정된다
 * (재시도해도 같은 eventId — 소비자는 at-least-once로 받는다).
 */
@Entity
@Table(name = "event_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 48, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public static EventOutbox of(String eventId, String eventType, byte[] payload, Instant now) {
        EventOutbox row = new EventOutbox();
        row.eventId = eventId;
        row.eventType = eventType;
        row.payload = payload;
        row.createdAt = now;
        row.attempts = 0;
        row.nextAttemptAt = now;
        return row;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    /** 실패 한 번 — 시도 횟수를 올리고 다음 시도를 미룬다. 미룰 간격은 호출자가 백오프 규칙으로 정한다 */
    public void markFailed(Instant now, Duration retryAfter, String error) {
        this.attempts += 1;
        this.nextAttemptAt = now.plus(retryAfter);
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
