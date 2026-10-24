package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 프로젝트 계획 설정(V39) — 사이클(스프린트 자동화)과 추정 척도. 행이 없으면 기본값이다
 * ({@link com.platform.almbackend.planning.PlanningSettings#DEFAULTS}). 검증·정규화는 서비스가 하고 여기는 담기만 한다.
 */
@Entity
@Table(name = "project_planning")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectPlanning {
    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "auto_create_next", nullable = false)
    private boolean autoCreateNext;

    @Column(name = "sprint_length_days", nullable = false)
    private int sprintLengthDays;

    @Column(name = "cooldown_days", nullable = false)
    private int cooldownDays;

    /** next | backlog */
    @Column(name = "default_carry_over", nullable = false, length = 8)
    private String defaultCarryOver;

    /** fibonacci | tshirt | linear | custom */
    @Column(name = "estimation_scale", nullable = false, length = 12)
    private String estimationScale;

    /** custom 척도 값(JSON 숫자 배열, 오름차순) */
    @Column(name = "custom_values", columnDefinition = "text")
    private String customValues;

    /** T셔츠 크기 → 포인트(JSON 객체 XS·S·M·L·XL) */
    @Column(name = "tshirt_points", columnDefinition = "text")
    private String tshirtPoints;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProjectPlanning of(long projectId) {
        ProjectPlanning planning = new ProjectPlanning();
        planning.projectId = projectId;
        planning.version = 0;
        return planning;
    }

    public void apply(boolean autoCreateNext, int lengthDays, int cooldownDays, String defaultCarryOver,
                      String scale, String customValues, String tshirtPoints, Instant now) {
        this.autoCreateNext = autoCreateNext;
        this.sprintLengthDays = lengthDays;
        this.cooldownDays = cooldownDays;
        this.defaultCarryOver = defaultCarryOver;
        this.estimationScale = scale;
        this.customValues = customValues;
        this.tshirtPoints = tshirtPoints;
        this.updatedAt = now;
        this.version += 1;
    }
}
