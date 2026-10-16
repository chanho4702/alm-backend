package com.platform.almbackend.repository;

import com.platform.almbackend.domain.EventOutbox;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, Long> {

    /**
     * 미발행 큐 앞부분을 잠가 가져온다(id 순). lock.timeout=-2는 SKIP LOCKED — 인스턴스가 여럿이면
     * 다른 워커가 쥔 행은 건너뛴다. 최대 시도를 넘긴 행은 큐에서 빠진다(last_error와 함께 남는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select o from EventOutbox o
             where o.publishedAt is null and o.attempts < :maxAttempts
             order by o.id asc
            """)
    List<EventOutbox> lockPending(@Param("maxAttempts") int maxAttempts, Limit limit);

    long countByPublishedAtIsNullAndAttemptsLessThan(int maxAttempts);

    /** 보존 기간이 지난 발행분 정리 — 미발행·포기한 행은 지우지 않는다 */
    @Modifying
    @Query("delete from EventOutbox o where o.publishedAt is not null and o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
