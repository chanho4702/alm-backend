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

/** 프로젝트에 등록한 코드 저장소(V41) — 커밋 수집이 "이 저장소의 커밋은 이 프로젝트 것"으로 읽는다. url은 정규화된 값이다. */
@Entity
@Table(name = "project_code_repository",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_code_repository_url", columnNames = {"project_id", "url"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeRepository {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 300)
    private String url;

    @Column(name = "default_branch", nullable = false, length = 100)
    private String defaultBranch;

    @Column(nullable = false)
    private Integer position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static CodeRepository of(long projectId, String name, String url, String defaultBranch, int position, Instant createdAt) {
        CodeRepository repo = new CodeRepository();
        repo.projectId = projectId;
        repo.name = name;
        repo.url = url;
        repo.defaultBranch = defaultBranch;
        repo.position = position;
        repo.createdAt = createdAt;
        return repo;
    }
}
