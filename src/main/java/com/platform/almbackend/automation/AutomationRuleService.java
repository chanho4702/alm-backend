package com.platform.almbackend.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.AutomationRule;
import com.platform.almbackend.domain.AutomationRun;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.AutomationRuleRepository;
import com.platform.almbackend.repository.AutomationRunRepository;
import com.platform.almbackend.search.aql.AqlSyntax;
import com.platform.almbackend.settings.SchemeService;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;

/**
 * 자동화 규칙 관리 — 프로젝트 ADMIN만 보고 고친다(지라: 프로젝트 관리자). 저장한 사람이 실행 주체가 된다:
 * 규칙은 그 사람의 권한 밖의 일을 할 수 없고, 그 사람이 권한을 잃으면 실행이 실패로 남는다(fail-closed).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AutomationRuleService {

    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_AQL = 4000;
    private static final int MAX_RULES_PER_PROJECT = 100;
    private static final int DEFAULT_RUNS = 50;
    private static final int MAX_RUNS = 200;

    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final ProjectService projects;
    private final SchemeService settings;
    private final ObjectMapper json;

    @Schema(description = "자동화 규칙 만들기")
    public record RuleRequest(
            @Schema(description = "이름(1~80자)", example = "완료되면 플래그 해제") String name,
            @Schema(description = "설명(최대 500자)") String description,
            @Schema(description = "켜기(생략하면 켬)", example = "true") Boolean enabled,
            AutomationSpec.Trigger trigger,
            @Schema(description = "조건(AQL) — 맞는 이슈에만 실행. 예약 트리거는 대상 검색이라 필수. ORDER BY는 예약에서만 의미가 있다",
                    example = "flagged = true") String condition,
            List<AutomationSpec.Action> actions,
            @Schema(description = "다른 규칙이 만든 변경에도 반응할지(기본 false — 루프 방지)", example = "false") Boolean allowRuleTrigger) {}

    @Schema(description = "자동화 규칙 고치기 — 통째로 바꾼다(만들기와 같은 항목 + expectedVersion)")
    public record RuleUpdateRequest(
            String name, String description, Boolean enabled, AutomationSpec.Trigger trigger, String condition,
            List<AutomationSpec.Action> actions, Boolean allowRuleTrigger,
            @Schema(description = "직전에 읽은 버전. 다르면 409", example = "1")
            @NotNull(message = "expectedVersion이 필요합니다") Integer expectedVersion) {
        RuleRequest body() {
            return new RuleRequest(name, description, enabled, trigger, condition, actions, allowRuleTrigger);
        }
    }

    @Schema(description = "자동화 규칙")
    public record RuleResponse(
            @Schema(example = "5") long id,
            @Schema(example = "7") long projectId,
            String name,
            String description,
            boolean enabled,
            @Schema(description = "자동으로 꺼진 이유(연속 실패·실행 상한). 사람이 다시 켜면 비워진다") String disabledReason,
            AutomationSpec.Trigger trigger,
            String condition,
            List<AutomationSpec.Action> actions,
            boolean allowRuleTrigger,
            @Schema(description = "실행 주체 — 마지막으로 저장한 사람. 액션은 이 사람의 권한으로 돈다", example = "42") long actorId,
            long createdBy,
            @Schema(description = "예약 트리거의 다음 실행 시각") Instant nextRunAt,
            Instant lastRunAt,
            int consecutiveFailures,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(description = "자동화 실행 이력 한 줄")
    public record RunResponse(
            long id, long ruleId, long projectId,
            @Schema(description = "대상 이슈. 예약 회차 요약이면 null") Long issueId,
            String issueKey, String triggerType,
            @Schema(description = "SUCCESS·FAILED·LOOP_BLOCKED·RATE_LIMITED") String outcome,
            @Schema(description = "연쇄 깊이(1 = 사람의 변경에 대한 첫 반응)") int depth,
            int actionsRun, String message, Instant startedAt, Instant finishedAt) {
        static RunResponse from(AutomationRun run) {
            return new RunResponse(run.getId(), run.getRuleId(), run.getProjectId(), run.getIssueId(), run.getIssueKey(),
                    run.getTriggerType(), run.getOutcome(), run.getDepth(), run.getActionsRun(), run.getMessage(),
                    run.getStartedAt(), run.getFinishedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list(long userId, long projectId) {
        projects.require(userId, projectId, AlmAction.ADMIN);
        return rules.findByProjectIdOrderByIdAsc(projectId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RuleResponse get(long userId, long ruleId) {
        AutomationRule rule = require(ruleId);
        projects.require(userId, rule.getProjectId(), AlmAction.ADMIN);
        return response(rule);
    }

    public RuleResponse create(long userId, long projectId, RuleRequest request) {
        projects.require(userId, projectId, AlmAction.ADMIN);
        if (rules.findByProjectIdOrderByIdAsc(projectId).size() >= MAX_RULES_PER_PROJECT) {
            throw new IllegalArgumentException("자동화 규칙은 프로젝트당 " + MAX_RULES_PER_PROJECT + "개까지 만들 수 있습니다");
        }
        Instant now = now();
        AutomationRule rule = AutomationRule.of(projectId, userId, now);
        define(rule, userId, request, now, false);
        return response(rules.saveAndFlush(rule));
    }

    public RuleResponse update(long userId, long ruleId, RuleUpdateRequest request) {
        AutomationRule rule = require(ruleId);
        projects.require(userId, rule.getProjectId(), AlmAction.ADMIN);
        if (!rule.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 규칙을 수정했습니다. 현재 "
                    + rule.getVersion() + ", 요청 " + request.expectedVersion());
        }
        define(rule, userId, request.body(), now(), true);
        rules.flush();
        return response(rule);
    }

    public void delete(long userId, long ruleId) {
        AutomationRule rule = require(ruleId);
        projects.require(userId, rule.getProjectId(), AlmAction.ADMIN);
        rules.delete(rule);
    }

    @Transactional(readOnly = true)
    public List<RunResponse> runs(long userId, long ruleId, Integer limit) {
        AutomationRule rule = require(ruleId);
        projects.require(userId, rule.getProjectId(), AlmAction.ADMIN);
        return runs.findByRuleIdOrderByStartedAtDescIdDesc(ruleId, Limit.of(limit(limit))).stream()
                .map(RunResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RunResponse> projectRuns(long userId, long projectId, Integer limit) {
        projects.require(userId, projectId, AlmAction.ADMIN);
        return runs.findByProjectIdOrderByStartedAtDescIdDesc(projectId, Limit.of(limit(limit))).stream()
                .map(RunResponse::from).toList();
    }

    // ── 검증 ──

    private void define(AutomationRule rule, long userId, RuleRequest request, Instant now, boolean bumpVersion) {
        long projectId = rule.getProjectId();
        Predicate<String> validStatus = status -> settings.body(projectId).statuses().stream().anyMatch(s -> s.id().equals(status));
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("규칙 이름을 입력하세요");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("규칙 이름은 " + MAX_NAME + "자 이하여야 합니다");
        String description = request.description() == null ? "" : request.description().trim();
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("규칙 설명은 " + MAX_DESCRIPTION + "자 이하여야 합니다");
        }
        AutomationSpec.Trigger trigger = AutomationSpec.validTrigger(request.trigger(), validStatus);
        List<AutomationSpec.Action> actions = new java.util.ArrayList<>();
        for (AutomationSpec.Action action : AutomationSpec.validActions(request.actions(), validStatus)) {
            // 우선순위 값은 이 프로젝트에서 쓸 수 있어야 한다 — 실행 때 처음 알면 규칙이 실패만 쌓는다(정규화한 id로 저장)
            if (AutomationSpec.SET_FIELD.equals(action.type()) && "priority".equals(action.field())) {
                String id = settings.resolvePriority(projectId, String.valueOf(action.value()));
                action = new AutomationSpec.Action(action.type(), action.field(), id, null, null, null, null, null, null, null, null, null);
            }
            actions.add(action);
        }
        String condition = request.condition() == null || request.condition().isBlank() ? null : request.condition().trim();
        if (condition != null) {
            if (condition.length() > MAX_AQL) throw new IllegalArgumentException("조건은 " + MAX_AQL + "자 이하여야 합니다");
            AqlSyntax.requireValid(condition);
        }
        boolean scheduled = AutomationSpec.SCHEDULED.equals(trigger.type());
        if (scheduled && condition == null) {
            throw new IllegalArgumentException("예약 규칙에는 대상을 고르는 조건(AQL)이 필요합니다");
        }
        Instant nextRunAt = scheduled ? AutomationSpec.nextRun(trigger.cron(), now) : null;
        rule.define(userId, name, description, request.enabled() == null || request.enabled(), trigger.type(),
                write(trigger), condition, write(actions), Boolean.TRUE.equals(request.allowRuleTrigger()),
                nextRunAt, now, bumpVersion);
    }

    private AutomationRule require(long ruleId) {
        return rules.findById(ruleId).orElseThrow(() -> new NotFoundException("자동화 규칙을 찾을 수 없습니다: " + ruleId));
    }

    private RuleResponse response(AutomationRule rule) {
        AutomationSpec.Trigger trigger;
        List<AutomationSpec.Action> actions;
        try {
            trigger = json.readValue(rule.getTriggerJson(), AutomationSpec.Trigger.class);
            actions = List.of(json.readValue(rule.getActionsJson(), AutomationSpec.Action[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("자동화 규칙 본문을 읽지 못했습니다", e);
        }
        return new RuleResponse(rule.getId(), rule.getProjectId(), rule.getName(), rule.getDescription(), rule.isEnabled(),
                rule.getDisabledReason(), trigger, rule.getConditionAql(), actions, rule.isAllowRuleTrigger(),
                rule.getActorId(), rule.getCreatedBy(), rule.getNextRunAt(), rule.getLastRunAt(),
                rule.getConsecutiveFailures(), rule.getVersion(), rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("자동화 규칙 본문을 저장하지 못했습니다", e);
        }
    }

    private static int limit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? DEFAULT_RUNS : limit, MAX_RUNS));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
