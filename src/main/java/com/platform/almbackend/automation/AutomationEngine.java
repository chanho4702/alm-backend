package com.platform.almbackend.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.AutomationRule;
import com.platform.almbackend.domain.AutomationRun;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.repository.AutomationRuleRepository;
import com.platform.almbackend.repository.AutomationRunRepository;
import com.platform.almbackend.search.aql.AqlException;
import com.platform.almbackend.search.aql.AqlSearchService;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 자동화 실행기 — 이벤트를 받아 맞는 규칙을 고르고, 규칙마다 자기 트랜잭션에서 조건을 보고 액션을 돌린다.
 *
 * <h2>안전장치</h2>
 * <ol>
 *   <li><b>규칙이 만든 변경은 기본적으로 규칙을 깨우지 않는다</b> — 지라 "다른 규칙의 트리거 허용" 기본값과 같다.
 *       {@code allowRuleTrigger}를 켠 규칙만 연쇄에 반응한다.</li>
 *   <li><b>연쇄 깊이 상한</b>({@code platform.alm.automation.max-depth}, 기본 3) — 넘으면 LOOP_BLOCKED로 남기고 멈춘다.</li>
 *   <li><b>같은 연쇄 안 같은 (규칙, 이슈)는 한 번</b> — A가 B를, B가 A를 깨우는 핑퐁을 깊이와 무관하게 끊는다.</li>
 *   <li><b>규칙당 분당 실행 상한</b>({@code rate-per-minute}, 기본 30) — 넘으면 RATE_LIMITED를 남기고 규칙을 끈다.</li>
 *   <li><b>연속 실패 상한</b>({@code max-failures}, 기본 5) — 넘으면 규칙을 끄고 이유를 남긴다. 권한 서비스 불능(503)은
 *       규칙 잘못이 아니라 세지 않는다.</li>
 *   <li><b>원자성</b> — 한 실행의 액션은 한 트랜잭션이다. 하나라도 실패하면 앞의 액션도 되돌린다.</li>
 * </ol>
 *
 * <p>연쇄 정보는 스레드 로컬({@link #CHAIN})이다: 액션이 낸 이벤트는 그 실행 트랜잭션의 커밋 뒤 <b>같은 스레드</b>에서
 * 리스너로 돌아오므로, 거기서 지금 연쇄를 읽어 다음 실행에 넘긴다(비동기 실행기에도 작업과 함께 넘긴다).
 */
@Slf4j
@Component
public class AutomationEngine {

    /** 지금 실행 중인 연쇄 — 사람의 요청 스레드에서는 비어 있다 */
    private static final ThreadLocal<Chain> CHAIN = new ThreadLocal<>();

    /** 연쇄 — 깊이(1 = 사람이 한 일에 대한 첫 반응)와 이미 돈 (규칙, 이슈) */
    record Chain(int depth, Set<String> visited) {
        Chain next(String key) {
            Set<String> seen = new HashSet<>(visited);
            seen.add(key);
            return new Chain(depth + 1, Set.copyOf(seen));
        }
    }

    /** 규칙을 깨운 일 — 예약 실행은 이슈가 하나씩 주어진다 */
    public record Trigger(String type, long projectId, long issueId, long actorId, String fromStatus, String toStatus,
                          Set<String> changedFields) {}

    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final AutomationActions actions;
    private final AqlSearchService aql;
    private final AutomationIssueOps issues;
    private final TaskExecutor executor;
    private final TransactionTemplate newTransaction;
    private final ObjectMapper json;
    private final int maxDepth;
    private final int ratePerMinute;
    private final int maxFailures;

    public AutomationEngine(AutomationRuleRepository rules, AutomationRunRepository runs, AutomationActions actions,
                            AqlSearchService aql, AutomationIssueOps issues,
                            PlatformTransactionManager transactionManager, ObjectMapper json,
                            @Value("${platform.alm.automation.max-depth:3}") int maxDepth,
                            @Value("${platform.alm.automation.rate-per-minute:30}") int ratePerMinute,
                            @Value("${platform.alm.automation.max-failures:5}") int maxFailures,
                            @Value("${platform.alm.automation.async:true}") boolean async,
                            @Value("${platform.alm.automation.threads:2}") int threads,
                            @Value("${platform.alm.automation.queue:500}") int queue) {
        this.rules = rules;
        this.runs = runs;
        this.actions = actions;
        this.aql = aql;
        this.issues = issues;
        this.executor = executor(async, threads, queue);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.maxDepth = maxDepth;
        this.ratePerMinute = ratePerMinute;
        this.maxFailures = maxFailures;
    }

    /**
     * 실행기 — 기본은 작은 스레드 풀 + 유한 대기열: 사람의 요청이 규칙 실행을 기다리지 않고, 몰려도 메모리가 무한히 늘지 않는다
     * (가득 차면 그 실행은 FAILED "대기열이 가득"으로 남는다). {@code async=false}면 부른 스레드에서 바로 돈다(테스트용).
     * 빈으로 등록하지 않는다 — TaskExecutor 빈이 생기면 Boot 기본 applicationTaskExecutor가 빠진다.
     */
    private static TaskExecutor executor(boolean async, int threads, int queue) {
        if (!async) return new SyncTaskExecutor();
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix("alm-automation-");
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setQueueCapacity(queue);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        pool.initialize();
        return pool;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ThreadPoolTaskExecutor pool) pool.shutdown();
    }

    // ── 이벤트 → 규칙 고르기 ──

    /** 리스너가 부른다(커밋 뒤). 맞는 규칙마다 실행을 실행기에 넘긴다 */
    public void dispatch(Trigger trigger) {
        Chain chain = CHAIN.get();
        int depth = chain == null ? 0 : chain.depth();
        List<AutomationRule> candidates = rules.findByProjectIdAndTriggerTypeAndEnabledTrue(trigger.projectId(), trigger.type());
        for (AutomationRule rule : candidates) {
            AutomationSpec.Trigger spec = readTrigger(rule);
            if (spec == null || !matches(spec, trigger)) continue;
            if (chain != null && !rule.isAllowRuleTrigger()) continue; // 규칙이 만든 변경 — 기본은 반응하지 않는다
            String key = rule.getId() + ":" + trigger.issueId();
            if (chain != null && chain.visited().contains(key)) {
                record(rule.getId(), trigger.issueId(), AutomationRun.LOOP_BLOCKED, depth + 1, 0,
                        "같은 연쇄에서 이 이슈에 이미 실행돼 멈췄습니다(루프 방지)");
                continue;
            }
            if (depth >= maxDepth) {
                record(rule.getId(), trigger.issueId(), AutomationRun.LOOP_BLOCKED, depth + 1, 0,
                        "규칙 연쇄가 " + maxDepth + "단계를 넘어 멈췄습니다(루프 방지)");
                continue;
            }
            Chain next = (chain == null ? new Chain(0, Set.of()) : chain).next(key);
            long ruleId = rule.getId();
            try {
                executor.execute(() -> withChain(next, () -> runForIssue(ruleId, trigger, next.depth())));
            } catch (TaskRejectedException e) {
                record(ruleId, trigger.issueId(), AutomationRun.FAILED, next.depth(), 0, "실행 대기열이 가득 차 건너뛰었습니다");
            }
        }
    }

    static boolean matches(AutomationSpec.Trigger spec, Trigger trigger) {
        return switch (spec.type()) {
            case AutomationSpec.ISSUE_TRANSITIONED ->
                    (spec.fromStatuses().isEmpty() || spec.fromStatuses().contains(trigger.fromStatus()))
                            && (spec.toStatuses().isEmpty() || spec.toStatuses().contains(trigger.toStatus()));
            case AutomationSpec.FIELD_CHANGED -> trigger.changedFields() != null
                    && spec.fields().stream().anyMatch(trigger.changedFields()::contains);
            default -> true;
        };
    }

    // ── 한 번의 실행 ──

    /** 이벤트 실행 — 상한·조건을 보고 액션을 돌린다. 결과는 따로 남긴다 */
    void runForIssue(long ruleId, Trigger trigger, int depth) {
        AutomationRule rule = rules.findById(ruleId).orElse(null);
        if (rule == null || !rule.isEnabled()) return;
        Instant started = now();
        long recent = runs.countByRuleIdAndStartedAtAfterAndOutcomeIn(ruleId, started.minusSeconds(60),
                List.of(AutomationRun.SUCCESS, AutomationRun.FAILED));
        if (recent >= ratePerMinute) {
            rateLimited(ruleId, trigger.issueId(), depth);
            return;
        }
        Outcome outcome = execute(rule, trigger.issueId(), true);
        if (outcome == null) return; // 조건에 안 맞았다 — 남기지 않는다
        finish(ruleId, trigger.issueId(), depth, outcome, started);
    }

    /** 예약 실행의 이슈 하나 — 조건은 대상 검색이 이미 봤다 */
    Outcome runScheduledIssue(AutomationRule rule, long issueId) {
        Chain chain = new Chain(1, Set.of(rule.getId() + ":" + issueId));
        Outcome[] result = new Outcome[1];
        withChain(chain, () -> result[0] = execute(rule, issueId, false));
        return result[0];
    }

    /** 실행 결과 — 성공이면 error가 null */
    record Outcome(int actionsRun, String error, boolean countsAsFailure, String issueKey) {
        boolean ok() { return error == null; }
    }

    /**
     * 조건 + 액션을 새 트랜잭션 하나에서. 조건이 안 맞으면 null. 실패하면 트랜잭션이 되돌아가고 오류를 담아 돌려준다.
     */
    private Outcome execute(AutomationRule rule, long issueId, boolean checkCondition) {
        List<AutomationSpec.Action> steps = readActions(rule);
        int[] done = new int[1];
        String[] key = new String[1];
        try {
            Boolean matched = newTransaction.execute(status -> {
                Issue issue = issues.require(issueId);
                key[0] = issue.getKey();
                if (checkCondition && rule.getConditionAql() != null
                        && aql.matchingIssueIds(rule.getActorId(), rule.getProjectId(), rule.getConditionAql(), issueId, 1).isEmpty()) {
                    return false;
                }
                for (AutomationSpec.Action step : steps) {
                    actions.apply(rule, issueId, step);
                    done[0]++;
                }
                return true;
            });
            if (!Boolean.TRUE.equals(matched)) return null;
            return new Outcome(done[0], null, false, key[0]);
        } catch (ServiceUnavailableException e) {
            return new Outcome(0, "권한 서비스에 연결할 수 없어 실행하지 못했습니다", false, key[0]);
        } catch (AqlException e) {
            return new Outcome(0, "조건(AQL)을 실행하지 못했습니다: " + e.getMessage(), true, key[0]);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String where = done[0] < steps.size() ? (done[0] + 1) + "번째 액션(" + steps.get(done[0]).type() + ") 실패: " : "";
            if (!(e instanceof IllegalArgumentException || e instanceof com.platform.common.error.ForbiddenException
                    || e instanceof com.platform.common.error.NotFoundException || e instanceof com.platform.common.error.ConflictException)) {
                log.warn("자동화 규칙 {} 실행 중 예외 (issue {})", rule.getId(), issueId, e);
            }
            return new Outcome(0, where + message, true, key[0]);
        }
    }

    private void finish(long ruleId, long issueId, int depth, Outcome outcome, Instant started) {
        newTransaction.executeWithoutResult(status -> {
            AutomationRule rule = rules.findById(ruleId).orElse(null);
            if (rule == null) return;
            Instant at = now();
            if (outcome.ok()) {
                rule.succeeded(at);
                runs.save(AutomationRun.of(rule, issueId, outcome.issueKey(), AutomationRun.SUCCESS, depth,
                        outcome.actionsRun(), null, started, at));
            } else {
                String message = outcome.error();
                if (outcome.countsAsFailure() && rule.failed(at, maxFailures)) message += " — " + rule.getDisabledReason();
                runs.save(AutomationRun.of(rule, issueId, outcome.issueKey(), AutomationRun.FAILED, depth,
                        outcome.actionsRun(), message, started, at));
            }
        });
    }

    private void rateLimited(long ruleId, long issueId, int depth) {
        newTransaction.executeWithoutResult(status -> {
            AutomationRule rule = rules.findById(ruleId).orElse(null);
            if (rule == null || !rule.isEnabled()) return;
            String reason = "1분에 " + ratePerMinute + "회를 넘게 실행돼 자동으로 꺼졌습니다";
            rule.disable(reason);
            Instant at = now();
            runs.save(AutomationRun.of(rule, issueId, null, AutomationRun.RATE_LIMITED, depth, 0, reason, at, at));
        });
    }

    /** 실행하지 않고 결과만 남긴다(루프 차단·대기열 가득) */
    private void record(long ruleId, Long issueId, String outcome, int depth, int actionsRun, String message) {
        try {
            newTransaction.executeWithoutResult(status -> rules.findById(ruleId).ifPresent(rule -> {
                Instant at = now();
                runs.save(AutomationRun.of(rule, issueId, null, outcome, depth, actionsRun, message, at, at));
            }));
        } catch (RuntimeException e) {
            log.warn("자동화 실행 기록 실패 (rule {}, outcome {})", ruleId, outcome, e);
        }
    }

    /** 예약 실행 한 회차의 요약을 남긴다 */
    void recordScheduled(long ruleId, int depth, int processed, int failed, String firstError, int actionsRun,
                         boolean countFailure, Instant started) {
        newTransaction.executeWithoutResult(status -> {
            AutomationRule rule = rules.findById(ruleId).orElse(null);
            if (rule == null) return;
            Instant at = now();
            String summary = "이슈 " + processed + "건 처리" + (failed > 0 ? ", " + failed + "건 실패: " + firstError : "");
            if (failed == 0) {
                rule.succeeded(at);
                runs.save(AutomationRun.of(rule, null, null, AutomationRun.SUCCESS, depth, actionsRun, summary, started, at));
            } else {
                if (countFailure && rule.failed(at, maxFailures)) summary += " — " + rule.getDisabledReason();
                runs.save(AutomationRun.of(rule, null, null, AutomationRun.FAILED, depth, actionsRun, summary, started, at));
            }
        });
    }

    private static void withChain(Chain chain, Runnable work) {
        Chain previous = CHAIN.get();
        CHAIN.set(chain);
        try {
            work.run();
        } finally {
            if (previous == null) CHAIN.remove();
            else CHAIN.set(previous);
        }
    }

    AutomationSpec.Trigger readTrigger(AutomationRule rule) {
        try {
            return json.readValue(rule.getTriggerJson(), AutomationSpec.Trigger.class);
        } catch (JsonProcessingException e) {
            log.warn("자동화 규칙 {}의 트리거를 읽지 못했습니다", rule.getId(), e);
            return null;
        }
    }

    List<AutomationSpec.Action> readActions(AutomationRule rule) {
        try {
            return json.readValue(rule.getActionsJson(), new TypeReference<List<AutomationSpec.Action>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("자동화 규칙의 액션을 읽지 못했습니다", e);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
