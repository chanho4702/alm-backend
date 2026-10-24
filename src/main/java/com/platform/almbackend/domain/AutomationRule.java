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

/**
 * 자동화 규칙(V36) — 프로젝트 단위 "트리거 → 조건(AQL) → 액션". 트리거·액션 본문은 서비스가 검증한 JSON이다.
 * 실행 주체({@code actorId})는 마지막으로 저장한 사람이다 — 액션은 그 사람의 권한으로 사람과 같은 경로를 탄다.
 */
@Entity
@Table(name = "automation_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutomationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    @Column(name = "trigger_json", nullable = false, columnDefinition = "text")
    private String triggerJson;

    @Column(name = "condition_aql", length = 4000)
    private String conditionAql;

    @Column(name = "actions_json", nullable = false, columnDefinition = "text")
    private String actionsJson;

    @Column(name = "allow_rule_trigger", nullable = false)
    private boolean allowRuleTrigger;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "disabled_reason", length = 200)
    private String disabledReason;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AutomationRule of(long projectId, long actorId, Instant now) {
        AutomationRule rule = new AutomationRule();
        rule.projectId = projectId;
        rule.actorId = actorId;
        rule.createdBy = actorId;
        rule.description = "";
        rule.version = 1;
        rule.createdAt = now;
        rule.updatedAt = now;
        return rule;
    }

    /**
     * 저장 — 값 검증은 서비스가 끝냈다. 저장한 사람이 새 실행 주체가 되고, 켜서 저장하면 자동 비활성 기록(이유·실패 수)을
     * 지운다(사람이 보고 다시 켠 것이다).
     */
    public void define(long actorId, String name, String description, boolean enabled, String triggerType,
                       String triggerJson, String conditionAql, String actionsJson, boolean allowRuleTrigger,
                       Instant nextRunAt, Instant now, boolean bumpVersion) {
        this.actorId = actorId;
        this.name = name;
        this.description = description;
        this.triggerType = triggerType;
        this.triggerJson = triggerJson;
        this.conditionAql = conditionAql;
        this.actionsJson = actionsJson;
        this.allowRuleTrigger = allowRuleTrigger;
        this.nextRunAt = nextRunAt;
        if (enabled && (!this.enabled || bumpVersion)) {
            this.consecutiveFailures = 0;
            this.disabledReason = null;
        }
        this.enabled = enabled;
        this.updatedAt = now;
        if (bumpVersion) this.version += 1;
    }

    /** 성공한 실행 — 연속 실패를 끊는다 */
    public void succeeded(Instant at) {
        this.lastRunAt = at;
        this.consecutiveFailures = 0;
    }

    /** 실패한 실행 — 상한에 닿으면 끄고 이유를 남긴다. 꺼졌으면 true */
    public boolean failed(Instant at, int maxFailures) {
        this.lastRunAt = at;
        this.consecutiveFailures += 1;
        if (consecutiveFailures >= maxFailures) {
            disable("연속 " + consecutiveFailures + "회 실패해 자동으로 꺼졌습니다");
            return true;
        }
        return false;
    }

    /** 자동 비활성 — version은 올리지 않는다(사람의 편집이 아니다. 열어 둔 편집 폼이 409가 되지 않게) */
    public void disable(String reason) {
        this.enabled = false;
        this.disabledReason = reason;
    }

    public void scheduleNext(Instant next) {
        this.nextRunAt = next;
    }
}
