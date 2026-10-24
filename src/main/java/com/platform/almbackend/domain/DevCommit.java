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

/**
 * 수집한 git 커밋 한 건(V41). (repositoryUrl, sha)가 유일하고 불변이다 — 삽입은 {@code DevCommitRepository.insertIfAbsent}
 * (ON CONFLICT DO NOTHING)로만 해서 같은 커밋을 다시 보내도 무해하다.
 */
@Entity
@Table(name = "dev_commit",
        uniqueConstraints = @UniqueConstraint(name = "uk_dev_commit_repository_sha", columnNames = {"repository_url", "sha"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DevCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_url", nullable = false, length = 300, updatable = false)
    private String repositoryUrl;

    @Column(name = "repository_name", nullable = false, length = 100, updatable = false)
    private String repositoryName;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha;

    @Column(name = "short_sha", nullable = false, length = 12, updatable = false)
    private String shortSha;

    @Column(nullable = false, length = 300, updatable = false)
    private String title;

    @Column(name = "body_excerpt", length = 2000, updatable = false)
    private String bodyExcerpt;

    @Column(name = "author_name", length = 200, updatable = false)
    private String authorName;

    @Column(name = "author_email", length = 320, updatable = false)
    private String authorEmail;

    @Column(name = "authored_at", nullable = false, updatable = false)
    private Instant authoredAt;

    @Column(length = 200, updatable = false)
    private String branch;

    @Column(nullable = false, length = 500, updatable = false)
    private String url;

    @Column(name = "ingested_by", nullable = false, updatable = false)
    private Long ingestedBy;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;
}
