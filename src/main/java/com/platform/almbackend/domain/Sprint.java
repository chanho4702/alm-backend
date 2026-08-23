package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "sprint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sprint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SprintState state;

    /** 프로젝트 안에서 몇 번째로 만들어졌는지 — 자동 이름과 목록 정렬의 기준이다. */
    @Column(name = "sprint_number", nullable = false, updatable = false)
    private Long sprintNumber;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Sprint of(long projectId, long sprintNumber, String name) {
        Sprint sprint = new Sprint();
        sprint.projectId = projectId;
        sprint.sprintNumber = sprintNumber;
        sprint.name = name;
        sprint.state = SprintState.PLANNED;
        sprint.version = 1;
        return sprint;
    }

    public void start(Instant now) {
        if (state != SprintState.PLANNED) {
            throw new IllegalStateException("계획 상태의 스프린트만 시작할 수 있습니다");
        }
        state = SprintState.ACTIVE;
        startedAt = now;
        version += 1;
    }

    public void complete(Instant now) {
        if (state != SprintState.ACTIVE) {
            throw new IllegalStateException("진행 중인 스프린트만 완료할 수 있습니다");
        }
        state = SprintState.DONE;
        completedAt = now;
        version += 1;
    }

    public void rename(String name) {
        this.name = name;
        this.version += 1;
    }
}
