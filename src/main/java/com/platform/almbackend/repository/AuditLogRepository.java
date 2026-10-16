package com.platform.almbackend.repository;

import com.platform.almbackend.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 필터는 전부 선택 — null이면 걸지 않는다. 최신순. since는 coalesce(PostgreSQL이 시각 null 파라미터 타입을 못 정함) */
    @Query("""
            select a from AuditLog a
            where (:type is null or a.eventType = :type)
              and (:actorId is null or a.actorId = :actorId)
              and (:projectId is null or a.projectId = :projectId)
              and a.occurredAt >= coalesce(:since, a.occurredAt)
            order by a.occurredAt desc, a.id desc
            """)
    Page<AuditLog> search(
            @Param("type") String type,
            @Param("actorId") Long actorId,
            @Param("projectId") Long projectId,
            @Param("since") Instant since,
            Pageable pageable);
}
