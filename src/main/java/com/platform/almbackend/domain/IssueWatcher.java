package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** 이슈 워처 — (이슈, 사용자) 한 쌍. 보고자·담당자는 자동, 나머지는 스스로 넣고 뺀다. */
@Entity
@Table(name = "issue_watcher")
@IdClass(IssueWatcher.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueWatcher {
    @Id
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static IssueWatcher of(long issueId, long userId, Instant createdAt) {
        IssueWatcher watcher = new IssueWatcher();
        watcher.issueId = issueId;
        watcher.userId = userId;
        watcher.createdAt = createdAt;
        return watcher;
    }

    public static final class Key implements Serializable {
        private Long issueId;
        private Long userId;

        public Key() {}

        public Key(Long issueId, Long userId) {
            this.issueId = issueId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(issueId, key.issueId) && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(issueId, userId);
        }
    }
}
