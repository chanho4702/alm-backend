package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Pageable pageable);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") long userId);

    // ── 인박스(V40) — (sortAt, id) 내림차순 키셋. 커서가 없으면 호출자가 먼 미래·Long.MAX를 넘긴다(null 파라미터 타입 추론을 피한다) ──

    /** 기본 인박스 — 보관 제외, 지금 미뤄진 것 제외, 종류·읽지 않음 필터 */
    @Query("""
            select n from Notification n
             where n.userId = :userId and n.archivedAt is null
               and (n.snoozedUntil is null or n.snoozedUntil <= :now)
               and n.type in :types
               and (:unreadOnly = false or n.read = false)
               and (n.sortAt < :cursorAt or (n.sortAt = :cursorAt and n.id < :cursorId))
             order by n.sortAt desc, n.id desc""")
    List<Notification> inbox(@Param("userId") long userId, @Param("now") Instant now,
                             @Param("types") Collection<Notification.Type> types, @Param("unreadOnly") boolean unreadOnly,
                             @Param("cursorAt") Instant cursorAt, @Param("cursorId") long cursorId, Limit limit);

    /** 미뤄 둔 것 — 해제 시각이 아직 안 온 것 */
    @Query("""
            select n from Notification n
             where n.userId = :userId and n.archivedAt is null and n.snoozedUntil > :now
               and (n.sortAt < :cursorAt or (n.sortAt = :cursorAt and n.id < :cursorId))
             order by n.sortAt desc, n.id desc""")
    List<Notification> snoozed(@Param("userId") long userId, @Param("now") Instant now,
                               @Param("cursorAt") Instant cursorAt, @Param("cursorId") long cursorId, Limit limit);

    /** 보관함 */
    @Query("""
            select n from Notification n
             where n.userId = :userId and n.archivedAt is not null
               and (n.sortAt < :cursorAt or (n.sortAt = :cursorAt and n.id < :cursorId))
             order by n.sortAt desc, n.id desc""")
    List<Notification> archived(@Param("userId") long userId,
                                @Param("cursorAt") Instant cursorAt, @Param("cursorId") long cursorId, Limit limit);

    /** 인박스 배지 — 보이는(보관 아님·미뤄지지 않은) 읽지 않은 알림 수 */
    @Query("""
            select count(n) from Notification n
             where n.userId = :userId and n.archivedAt is null and n.read = false
               and (n.snoozedUntil is null or n.snoozedUntil <= :now)""")
    long countUnread(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.id in :ids and n.read = false")
    int markRead(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Query("update Notification n set n.read = false where n.userId = :userId and n.id in :ids and n.read = true")
    int markUnread(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Query("update Notification n set n.archivedAt = :at where n.userId = :userId and n.id in :ids and n.archivedAt is null")
    int archive(@Param("userId") long userId, @Param("ids") Collection<Long> ids, @Param("at") Instant at);

    @Modifying
    @Query("update Notification n set n.archivedAt = null where n.userId = :userId and n.id in :ids and n.archivedAt is not null")
    int unarchive(@Param("userId") long userId, @Param("ids") Collection<Long> ids);
}
