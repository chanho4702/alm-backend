package com.platform.almbackend.repository;

import com.platform.almbackend.domain.EventOutboxLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventOutboxLockRepository extends JpaRepository<EventOutboxLock, Integer> {

    /** 소유권 행을 잡는다 — 다른 트랜잭션이 쥐고 있으면 기다리지 않고 빈 결과(SKIP LOCKED). PostgreSQL·H2 공통 문법 */
    @Query(value = "select id from event_outbox_lock where id = :id for update skip locked", nativeQuery = true)
    List<Integer> tryLock(@Param("id") int id);
}
