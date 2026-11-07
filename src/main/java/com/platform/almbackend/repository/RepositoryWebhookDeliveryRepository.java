package com.platform.almbackend.repository;

import com.platform.almbackend.domain.RepositoryWebhookDelivery;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RepositoryWebhookDeliveryRepository extends JpaRepository<RepositoryWebhookDelivery, Long> {

    /**
     * 처음 보는 전달일 때만 넣는다 — 같은 (webhook_id, delivery_id)가 있으면 0(재전송). 같은 전달이 동시에 들어오면 늦은 쪽은
     * 먼저 넣은 트랜잭션이 끝날 때까지 기다린다: 그쪽이 커밋하면 0, 롤백하면(처리 실패) 이쪽이 넣고 처리한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO repository_webhook_delivery (webhook_id, delivery_id, event, outcome, message, received_at)"
            + " VALUES (:webhookId, :deliveryId, :event, :outcome, :message, :receivedAt) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(long webhookId, String deliveryId, String event, String outcome, String message, Instant receivedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RepositoryWebhookDelivery d set d.outcome = :outcome, d.message = :message
            where d.webhookId = :webhookId and d.deliveryId = :deliveryId
            """)
    int finish(long webhookId, String deliveryId, String outcome, String message);

    List<RepositoryWebhookDelivery> findByWebhookIdOrderByIdDesc(long webhookId, Limit limit);

    /** 보존 경계 — 최신 keep건 바로 다음(더 오래된) 행의 id. 없으면 아직 상한 안이다 */
    @Query(value = "SELECT id FROM repository_webhook_delivery WHERE webhook_id = :webhookId ORDER BY id DESC LIMIT 1 OFFSET :keep",
            nativeQuery = true)
    Optional<Long> idBeyond(long webhookId, int keep);

    @Modifying(flushAutomatically = true)
    @Query("delete from RepositoryWebhookDelivery d where d.webhookId = :webhookId and d.id <= :maxId")
    int deleteUpTo(long webhookId, long maxId);
}
