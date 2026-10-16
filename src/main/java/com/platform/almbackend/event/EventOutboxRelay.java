package com.platform.almbackend.event;

import com.platform.almbackend.domain.EventOutbox;
import com.platform.almbackend.repository.EventOutboxRepository;
import com.platform.proto.events.v1.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 아웃박스 워커 — 커밋된 이벤트를 id 순으로 Redis Streams에 내보낸다(at-least-once).
 *
 * <ul>
 *   <li>한 배치 = 한 트랜잭션: 미발행 행을 SKIP LOCKED로 잠가 가져와 발행·표시한다. 인스턴스가 여럿이어도 같은 행을 동시에 내보내지 않는다.</li>
 *   <li>순서: 앞 행이 실패하거나 아직 대기 중이면 그 배치는 거기서 멈춘다 — Redis 장애는 보통 전체 장애라 큐 전체가 함께 물러난다.</li>
 *   <li>재시도: 지수 백오프(base × 2^(시도-1), 상한 max). 최대 시도를 넘기면 큐에서 빠지고 last_error와 함께 남는다(오류 로그).</li>
 *   <li>정리: 보존 기간이 지난 발행분만 지운다.</li>
 * </ul>
 */
@Component
@Slf4j
public class EventOutboxRelay {
    /** 한 주기에 연달아 처리할 최대 배치 수 — 밀린 큐를 따라잡되 스케줄러 스레드를 오래 붙들지 않는다 */
    private static final int MAX_BATCHES_PER_POLL = 10;

    private final EventOutboxRepository outbox;
    private final ObjectProvider<EventPublisher> publisher;
    private final TransactionTemplate transaction;
    private final boolean pollingEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration retention;

    public EventOutboxRelay(
            EventOutboxRepository outbox,
            ObjectProvider<EventPublisher> publisher,
            PlatformTransactionManager transactionManager,
            @Value("${platform.events.outbox.polling-enabled:true}") boolean pollingEnabled,
            @Value("${platform.events.outbox.batch-size:100}") int batchSize,
            @Value("${platform.events.outbox.max-attempts:20}") int maxAttempts,
            @Value("${platform.events.outbox.base-backoff:1s}") Duration baseBackoff,
            @Value("${platform.events.outbox.max-backoff:5m}") Duration maxBackoff,
            @Value("${platform.events.outbox.retention:7d}") Duration retention) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.pollingEnabled = pollingEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${platform.events.outbox.poll-delay:1s}",
            initialDelayString = "${platform.events.outbox.initial-delay:5s}")
    public void poll() {
        if (!pollingEnabled) return;
        try {
            publishPending();
        } catch (RuntimeException e) {
            // DB 장애 등 — 다음 주기에 다시 본다. 행은 커밋된 채로 남아 있다
            log.warn("ALM 이벤트 아웃박스 처리 실패(다음 주기에 재시도)", e);
        }
    }

    /** 밀린 이벤트를 내보내고 이번에 발행한 수를 돌려준다. 테스트·수동 배출용으로도 쓴다 */
    public int publishPending() {
        EventPublisher target = publisher.getIfAvailable();
        if (target == null) return 0;
        int total = 0;
        for (int round = 0; round < MAX_BATCHES_PER_POLL; round++) {
            BatchResult result = transaction.execute(status -> publishBatch(target));
            if (result == null) break;
            total += result.published();
            if (result.stopped() || result.published() < batchSize) break;
        }
        return total;
    }

    private record BatchResult(int published, boolean stopped) {}

    private BatchResult publishBatch(EventPublisher target) {
        List<EventOutbox> rows = outbox.lockPending(maxAttempts, Limit.of(batchSize));
        Instant now = Instant.now();
        int published = 0;
        for (EventOutbox row : rows) {
            if (row.getNextAttemptAt().isAfter(now)) return new BatchResult(published, true);
            try {
                target.publish(EventEnvelope.parseFrom(row.getPayload()));
                row.markPublished(now);
                published++;
            } catch (Exception e) {
                Duration retryAfter = backoff(row.getAttempts() + 1);
                row.markFailed(now, retryAfter, e.getClass().getSimpleName() + ": " + e.getMessage());
                if (row.getAttempts() >= maxAttempts) {
                    log.error("ALM 이벤트 발행 포기 — 최대 시도 {}회 초과, 색인 재구축 필요: outboxId={} type={} eventId={}",
                            maxAttempts, row.getId(), row.getEventType(), row.getEventId(), e);
                } else {
                    log.warn("ALM 이벤트 발행 실패 — {} 뒤 재시도({}/{}): outboxId={} type={} eventId={} error={}",
                            retryAfter, row.getAttempts(), maxAttempts, row.getId(), row.getEventType(),
                            row.getEventId(), e.toString());
                }
                return new BatchResult(published, true);
            }
        }
        return new BatchResult(published, false);
    }

    /** attempt번째 실패 뒤 대기 — base × 2^(attempt-1), 상한 max */
    Duration backoff(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        Duration delay = baseBackoff.multipliedBy(1L << shift);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    /** 대기 중인(미발행·포기 전) 행 수 — 관측·테스트용 */
    public long pendingCount() {
        return outbox.countByPublishedAtIsNullAndAttemptsLessThan(maxAttempts);
    }

    @Scheduled(cron = "${platform.events.outbox.cleanup-cron:0 17 * * * *}")
    public void scheduledPurge() {
        if (!pollingEnabled) return;
        try {
            int deleted = purgePublished();
            if (deleted > 0) log.info("ALM 이벤트 아웃박스 정리: 발행분 {}건 삭제", deleted);
        } catch (RuntimeException e) {
            log.warn("ALM 이벤트 아웃박스 정리 실패", e);
        }
    }

    /** 보존 기간이 지난 발행분을 지우고 지운 수를 돌려준다 — 미발행·포기한 행은 남긴다 */
    public int purgePublished() {
        Integer deleted = transaction.execute(status ->
                outbox.deletePublishedBefore(Instant.now().minus(retention)));
        return deleted == null ? 0 : deleted;
    }
}
