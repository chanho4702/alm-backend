package com.platform.almbackend.event;

import com.platform.almbackend.admin.AuditLogService;
import com.platform.almbackend.domain.EventOutbox;
import com.platform.almbackend.repository.EventOutboxRepository;
import com.platform.proto.events.v1.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class EventRelay {
    private final ObjectProvider<EventPublisher> publisher;
    private final ObjectProvider<AuditLogService> audit;
    private final EventOutboxRepository outbox;

    /**
     * 발행은 커밋 뒤 — 이벤트를 지금 트랜잭션의 아웃박스에 한 줄 적고, Redis Streams로는
     * {@link EventOutboxRelay}가 커밋 뒤에 내보낸다(롤백되면 아웃박스 행도 함께 사라진다).
     * 요청 경로는 Redis를 기다리지 않고, Redis 장애 중에 커밋된 이벤트도 재시도로 살아남는다.
     * 감사 로그도 같은 트랜잭션에서 남긴다.
     */
    public void afterCommit(EventEnvelope event) {
        audit.ifAvailable(service -> service.record(event));
        // 이벤트 발행이 꺼진 설치(EVENTS_ENABLED=false)는 내보낼 곳이 없다 — 쌓아 두지 않는다
        if (publisher.getIfAvailable() == null) return;
        outbox.save(EventOutbox.of(
                event.getEventId(), event.getPayloadCase().name(), event.toByteArray(), Instant.now()));
    }
}
