package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 인앱 알림 한 줄. 문장은 서버가 만들지 않는다 — 상태 이름·사용자 이름은 프론트만 알기 때문에
 * 종류(type)와 부가값(detail: 상태 id 등)만 남기고 프론트가 문장을 만든다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
    public enum Type { ASSIGNED, STATUS_CHANGED, COMMENTED, MENTIONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "issue_id", updatable = false)
    private Long issueId;

    @Column(name = "issue_key", nullable = false, length = 40, updatable = false)
    private String issueKey;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private Type type;

    @Column(length = 200, updatable = false)
    private String detail;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification of(
            long userId, Issue issue, long actorId, Type type, String detail, Instant createdAt) {
        Notification notification = new Notification();
        notification.userId = userId;
        notification.issueId = issue.getId();
        notification.issueKey = issue.getKey();
        notification.actorId = actorId;
        notification.type = type;
        notification.detail = detail;
        notification.read = false;
        notification.createdAt = createdAt;
        return notification;
    }

    public void markRead() {
        this.read = true;
    }
}
