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
    /** AUTOMATION(2026-10-24) — 자동화 규칙의 "알림" 액션. detail에 규칙이 정한 문장(최대 200자)이 실린다. 인앱 전용(메일 없음) */
    public enum Type { ASSIGNED, STATUS_CHANGED, COMMENTED, MENTIONED, AUTOMATION }

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

    /** 인박스 정렬 기준(V40) — 처음엔 createdAt, 미루면 해제 시각. 해제 시각이 지나면 그 자리(위쪽)로 다시 올라온다 */
    @Column(name = "sort_at", nullable = false)
    private Instant sortAt;

    /** 미루기 해제 시각(V40). 지나기 전에는 기본 인박스에서 빠진다 */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    /** 보관 시각(V40) — 삭제 대신. 보관함 필터로만 보인다 */
    @Column(name = "archived_at")
    private Instant archivedAt;

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
        notification.sortAt = createdAt;
        return notification;
    }

    public void markRead() {
        this.read = true;
    }

    public void markUnread() {
        this.read = false;
    }

    /** 미루기 — 해제 시각에 읽지 않은 알림으로 다시 위에 뜬다 */
    public void snooze(Instant until) {
        this.snoozedUntil = until;
        this.sortAt = until;
        this.read = false;
    }

    /** 미루기 취소 — 원래 자리(createdAt)로 */
    public void unsnooze() {
        this.snoozedUntil = null;
        this.sortAt = createdAt;
    }

    public void archive(Instant at) {
        if (this.archivedAt == null) this.archivedAt = at;
    }

    public void unarchive() {
        this.archivedAt = null;
    }

    /** 지금 미뤄져 있어 기본 인박스에서 빠지는가 */
    public boolean isSnoozedAt(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }
}
