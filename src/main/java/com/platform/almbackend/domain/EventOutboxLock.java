package com.platform.almbackend.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아웃박스 발행 소유권 행(V26, id=1 한 줄) — 배치 트랜잭션이 잠가 쥐는 동안 다른 인스턴스는 발행하지 않는다.
 * 행 자체에는 값이 없다. 잠금만이 의미다.
 */
@Entity
@Table(name = "event_outbox_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutboxLock {
    public static final int OWNER_ROW = 1;

    @Id
    private Integer id;

    public static EventOutboxLock owner() {
        EventOutboxLock lock = new EventOutboxLock();
        lock.id = OWNER_ROW;
        return lock;
    }
}
