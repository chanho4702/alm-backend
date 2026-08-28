package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

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

    /** 스프린트 목표 — "무엇을 위한 스프린트인가". 비우면 null이다. */
    @Column(length = 255)
    private String goal;

    /** 계획된 기간. 실제 시작·완료 시각과 달리 계획 단계에서 정해지고 리포트의 시간축이 된다. */
    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

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

    /**
     * 계획 메타를 한 번에 고친다. 기간은 부분 지정(한쪽만)도 허용하되 역전은 막는다 —
     * DB 체크 제약과 같은 규칙을 엔티티가 먼저 판정해 한국어 사유를 준다.
     */
    public void editPlan(String name, String goal, LocalDate plannedStart, LocalDate plannedEnd) {
        if (plannedStart != null && plannedEnd != null && plannedStart.isAfter(plannedEnd)) {
            throw new IllegalArgumentException("시작 예정일은 종료 예정일보다 늦을 수 없습니다");
        }
        this.name = name;
        this.goal = goal;
        this.plannedStart = plannedStart;
        this.plannedEnd = plannedEnd;
        this.version += 1;
    }
}
