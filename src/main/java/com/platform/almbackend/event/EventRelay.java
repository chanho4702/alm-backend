package com.platform.almbackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventRelay {
    private final ObjectProvider<EventPublisher> publisher;

    public void afterCommit(EventEnvelope event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safePublish(event); }
            });
        } else {
            safePublish(event);
        }
    }

    private void safePublish(EventEnvelope event) {
        EventPublisher target = publisher.getIfAvailable();
        if (target == null) return;
        try {
            target.publish(event);
        } catch (Exception e) {
            log.error("ALM 이벤트 발행 실패 — 색인 유실됨(비차단): type={} id={}",
                    event.getPayloadCase(), event.getEventId(), e);
        }
    }
}

