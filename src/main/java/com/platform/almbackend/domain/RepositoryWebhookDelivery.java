package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 처리한 웹훅 전달 한 건(V44) — (webhookId, deliveryId) 유일. 삽입은 ON CONFLICT DO NOTHING으로만 한다(재전송 멱등). */
@Entity
@Table(name = "repository_webhook_delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_repository_webhook_delivery", columnNames = {"webhook_id", "delivery_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryWebhookDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "webhook_id", nullable = false, updatable = false)
    private Long webhookId;

    @Column(name = "delivery_id", nullable = false, length = 100, updatable = false)
    private String deliveryId;

    @Column(nullable = false, length = 40)
    private String event;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(length = 300)
    private String message;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
