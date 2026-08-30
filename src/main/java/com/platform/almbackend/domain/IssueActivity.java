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

/** 이슈 활동 한 줄 — 상세 "활동" 탭의 원천. 추가만 한다 */
@Entity
@Table(name = "issue_activity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "activity_type", nullable = false, length = 24, updatable = false)
    private String type;

    @Column(nullable = false, length = 500, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static IssueActivity of(long issueId, long actorId, String type, String detail, Instant at) {
        IssueActivity activity = new IssueActivity();
        activity.issueId = issueId;
        activity.actorId = actorId;
        activity.type = type;
        activity.detail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500));
        activity.occurredAt = at;
        return activity;
    }
}
