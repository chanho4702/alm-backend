package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key", nullable = false, unique = true, length = 12, updatable = false)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "last_issue_number", nullable = false)
    private Long lastIssueNumber;

    /** 프론트가 보고 있던 버전을 명시적으로 비교하는 수동 낙관적 락. */
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Project of(String key, String name, String description) {
        Project project = new Project();
        project.key = key;
        project.name = name;
        project.description = description;
        project.lastIssueNumber = 0L;
        project.version = 1;
        return project;
    }

    public void edit(String name, String description) {
        this.name = name;
        this.description = description;
        this.version += 1;
    }

    /** 프로젝트 row를 비관적으로 잠근 서비스 안에서만 호출한다. */
    public long nextIssueNumber() {
        this.lastIssueNumber += 1;
        return this.lastIssueNumber;
    }

    /** 보존 키(이관)가 쓴 번호 이상으로 카운터를 앞당긴다 — 이후 발급 키가 겹치지 않게 */
    public void reserveIssueNumber(long number) {
        if (number > this.lastIssueNumber) this.lastIssueNumber = number;
    }
}

