package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 이슈 ↔ 커밋 연결(V41, 다대다). 삽입은 {@code IssueCommitRepository.insertIfAbsent}로만 — 멱등 */
@Entity
@Table(name = "issue_commit",
        uniqueConstraints = @UniqueConstraint(name = "uk_issue_commit", columnNames = {"issue_id", "commit_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "commit_id", nullable = false, updatable = false)
    private Long commitId;

    @Column(name = "linked_by", nullable = false, updatable = false)
    private Long linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;
}
