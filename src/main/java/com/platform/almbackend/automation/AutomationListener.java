package com.platform.almbackend.automation;

import com.platform.almbackend.event.domain.IssueCreatedEvent;
import com.platform.almbackend.event.domain.IssueTransitionedEvent;
import com.platform.almbackend.event.domain.IssueUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 이슈 도메인 이벤트 → 자동화. <b>커밋 뒤</b>에만 반응한다(롤백된 변경에 규칙이 돌면 안 된다). 트랜잭션 밖에서 난 이벤트도
 * 받는다(fallbackExecution). 자동화가 실패해도 사람의 요청은 이미 커밋됐으므로 예외를 위로 올리지 않는다.
 *
 * <p>{@code platform.alm.automation.enabled=false}면 리스너가 없다 — 설치 단위로 자동화를 통째로 끌 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.alm.automation.enabled", havingValue = "true", matchIfMissing = true)
class AutomationListener {
    private final AutomationEngine engine;

    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueCreatedEvent event) {
        safely(new AutomationEngine.Trigger(AutomationSpec.ISSUE_CREATED, event.projectId(), event.issueId(),
                event.actorId(), null, null, null));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueTransitionedEvent event) {
        safely(new AutomationEngine.Trigger(AutomationSpec.ISSUE_TRANSITIONED, event.projectId(), event.issueId(),
                event.actorId(), event.fromStatus(), event.toStatus(), null));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueUpdatedEvent event) {
        safely(new AutomationEngine.Trigger(AutomationSpec.FIELD_CHANGED, event.projectId(), event.issueId(),
                event.actorId(), null, null, event.changedFields()));
    }

    private void safely(AutomationEngine.Trigger trigger) {
        try {
            engine.dispatch(trigger);
        } catch (RuntimeException e) {
            log.warn("자동화 디스패치 실패 (project {}, issue {}, {})", trigger.projectId(), trigger.issueId(), trigger.type(), e);
        }
    }
}
