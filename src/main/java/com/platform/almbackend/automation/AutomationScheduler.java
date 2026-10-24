package com.platform.almbackend.automation;

import com.platform.almbackend.domain.AutomationRule;
import com.platform.almbackend.repository.AutomationRuleRepository;
import com.platform.almbackend.repository.AutomationRunRepository;
import com.platform.almbackend.search.aql.AqlSearchService;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 예약 트리거 — 1분마다 때가 된 규칙을 집어 조건(AQL)에 맞는 이슈마다 액션을 돌린다.
 *
 * <ul>
 *   <li><b>한 회차는 한 노드만</b>: 다음 시각으로 옮기는 조건부 UPDATE({@link AutomationRuleRepository#claim})에 성공한 노드만 돈다.</li>
 *   <li><b>회차당 이슈 상한</b>({@code schedule-batch}, 기본 100) — 나머지는 다음 회차에 다시 맞으면 처리된다.</li>
 *   <li>밀린 회차는 따라잡지 않는다 — 서버가 멈췄다 켜지면 한 번만 돌고 다음 시각은 "지금 이후"로 잡는다.</li>
 *   <li>실행 이력 보존 30일 — 같은 주기에서 지난 것을 지운다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AutomationScheduler {

    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int MAX_RULES_PER_TICK = 50;

    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final AutomationEngine engine;
    private final AqlSearchService aql;
    private final TransactionTemplate transaction;
    private final boolean pollingEnabled;
    private final int batch;

    public AutomationScheduler(AutomationRuleRepository rules, AutomationRunRepository runs, AutomationEngine engine,
                               AqlSearchService aql, PlatformTransactionManager transactionManager,
                               @Value("${platform.alm.automation.schedule-polling-enabled:true}") boolean pollingEnabled,
                               @Value("${platform.alm.automation.schedule-batch:100}") int batch) {
        this.rules = rules;
        this.runs = runs;
        this.engine = engine;
        this.aql = aql;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.pollingEnabled = pollingEnabled;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${platform.alm.automation.schedule-poll-delay:60s}",
            initialDelayString = "${platform.alm.automation.schedule-initial-delay:30s}")
    public void poll() {
        if (!pollingEnabled) return;
        try {
            runDue(Instant.now());
            purgeOld(Instant.now());
        } catch (RuntimeException e) {
            log.warn("자동화 예약 실행 주기 실패(다음 주기에 재시도)", e);
        }
    }

    /** 때가 된 규칙을 돌리고 돌린 규칙 수를 돌려준다 — 테스트·수동 실행용으로도 쓴다 */
    public int runDue(Instant now) {
        int ran = 0;
        for (AutomationRule rule : rules.findDue(now, Limit.of(MAX_RULES_PER_TICK))) {
            AutomationSpec.Trigger trigger = engine.readTrigger(rule);
            if (trigger == null || trigger.cron() == null) continue;
            Instant next = AutomationSpec.nextRun(trigger.cron(), now);
            Integer claimed = transaction.execute(status -> rules.claim(rule.getId(), rule.getNextRunAt(), next));
            if (claimed == null || claimed == 0) continue; // 다른 노드가 먼저 집었다
            runOnce(rule, now);
            ran++;
        }
        return ran;
    }

    private void runOnce(AutomationRule rule, Instant started) {
        List<Long> targets;
        try {
            targets = aql.matchingIssueIds(rule.getActorId(), rule.getProjectId(), rule.getConditionAql(), null, batch);
        } catch (ServiceUnavailableException e) {
            engine.recordScheduled(rule.getId(), 1, 0, 1, "권한 서비스에 연결할 수 없어 대상을 찾지 못했습니다", 0, false, started);
            return;
        } catch (RuntimeException e) {
            engine.recordScheduled(rule.getId(), 1, 0, 1, "대상 검색(AQL) 실패: " + e.getMessage(), 0, true, started);
            return;
        }
        int processed = 0;
        int failed = 0;
        int actions = 0;
        boolean countFailure = false;
        String firstError = null;
        for (Long issueId : targets) {
            AutomationEngine.Outcome outcome = engine.runScheduledIssue(rule, issueId);
            if (outcome == null) continue;
            processed++;
            actions += outcome.actionsRun();
            if (!outcome.ok()) {
                failed++;
                countFailure |= outcome.countsAsFailure();
                if (firstError == null) firstError = (outcome.issueKey() == null ? "" : outcome.issueKey() + " ") + outcome.error();
            }
        }
        engine.recordScheduled(rule.getId(), 1, processed, failed, firstError, actions, countFailure, started);
    }

    public int purgeOld(Instant now) {
        Integer deleted = transaction.execute(status -> runs.deleteOlderThan(now.minus(RETENTION).truncatedTo(ChronoUnit.MICROS)));
        return deleted == null ? 0 : deleted;
    }
}
