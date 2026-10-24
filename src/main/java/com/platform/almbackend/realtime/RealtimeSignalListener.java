package com.platform.almbackend.realtime;

import com.platform.almbackend.event.domain.IssueCreatedEvent;
import com.platform.almbackend.event.domain.IssueDeletedEvent;
import com.platform.almbackend.event.domain.IssueRankedEvent;
import com.platform.almbackend.event.domain.IssueRestoredEvent;
import com.platform.almbackend.event.domain.IssueUpdatedEvent;
import com.platform.almbackend.event.domain.SprintChangedEvent;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 도메인 이벤트({@code event/domain}) → 실시간 무효화 신호. <b>커밋 뒤</b>에만 낸다 — 롤백된 변경으로 화면을 흔들지 않는다
 * (트랜잭션 밖 발행은 즉시, fallbackExecution).
 *
 * <p>전환은 따로 듣지 않는다: 상태가 바뀌면 {@code IssueTransitionedEvent}와 함께 {@code changedFields}에 status가 든
 * {@link IssueUpdatedEvent}가 반드시 같이 나가므로, 그것 하나를 {@code issue.transitioned}로 보낸다(한 변경 = 한 신호).
 */
@Slf4j
@RequiredArgsConstructor
public class RealtimeSignalListener {
    private final RealtimeBus bus;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IssueCreatedEvent e) {
        publish(new RealtimeSignal(null, "issue", "created", e.projectId(), e.issueId(), e.issueKey(), null,
                e.version(), null, e.actorId(), e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IssueUpdatedEvent e) {
        // 어휘 순서로 고정 — 받는 쪽 비교·테스트가 집합 순서에 흔들리지 않게
        List<String> fields = IssueDomainEventPublisher.CHANGED_FIELDS.stream().filter(e.changedFields()::contains).toList();
        String kind = fields.contains("status") ? "transitioned" : "updated";
        publish(new RealtimeSignal(null, "issue", kind, e.projectId(), e.issueId(), e.issueKey(), null,
                e.version(), fields, e.actorId(), e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IssueDeletedEvent e) {
        publish(new RealtimeSignal(null, "issue", e.archived() ? "archived" : "deleted", e.projectId(), e.issueId(),
                e.issueKey(), null, null, null, e.actorId(), e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IssueRestoredEvent e) {
        publish(new RealtimeSignal(null, "issue", "restored", e.projectId(), e.issueId(), e.issueKey(), null,
                e.version(), null, e.actorId(), e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IssueRankedEvent e) {
        publish(new RealtimeSignal(null, "issue", "ranked", e.projectId(), e.issueId(), e.issueKey(), e.sprintId(),
                e.version(), null, e.actorId(), e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SprintChangedEvent e) {
        publish(new RealtimeSignal(null, "sprint", e.kind(), e.projectId(), null, null, e.sprintId(),
                "deleted".equals(e.kind()) ? null : e.version(), null, e.actorId(), e.at()));
    }

    private void publish(RealtimeSignal signal) {
        try {
            bus.publish(signal);
        } catch (RuntimeException ex) {
            log.warn("실시간 신호 발행 실패 (project {}, {}.{})", signal.projectId(), signal.event(), signal.kind(), ex);
        }
    }
}
