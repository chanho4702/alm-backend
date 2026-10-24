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

/** 자동화 실행 이력(V37) — 추가만 한다. 보존 30일 */
@Entity
@Table(name = "automation_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutomationRun {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String LOOP_BLOCKED = "LOOP_BLOCKED";
    public static final String RATE_LIMITED = "RATE_LIMITED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private Long ruleId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "issue_id", updatable = false)
    private Long issueId;

    @Column(name = "issue_key", length = 40, updatable = false)
    private String issueKey;

    @Column(name = "trigger_type", nullable = false, length = 24, updatable = false)
    private String triggerType;

    @Column(nullable = false, length = 16, updatable = false)
    private String outcome;

    @Column(nullable = false, updatable = false)
    private int depth;

    @Column(name = "actions_run", nullable = false, updatable = false)
    private int actionsRun;

    @Column(length = 1000, updatable = false)
    private String message;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    public static AutomationRun of(AutomationRule rule, Long issueId, String issueKey, String outcome, int depth,
                                   int actionsRun, String message, Instant startedAt, Instant finishedAt) {
        AutomationRun run = new AutomationRun();
        run.ruleId = rule.getId();
        run.projectId = rule.getProjectId();
        run.issueId = issueId;
        run.issueKey = issueKey;
        run.triggerType = rule.getTriggerType();
        run.outcome = outcome;
        run.depth = depth;
        run.actionsRun = actionsRun;
        run.message = message == null ? null : (message.length() > 1000 ? message.substring(0, 1000) : message);
        run.startedAt = startedAt;
        run.finishedAt = finishedAt;
        return run;
    }
}
