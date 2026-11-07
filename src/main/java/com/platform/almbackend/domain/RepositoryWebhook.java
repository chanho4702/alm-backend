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

/**
 * 저장소 웹훅(V44) — 등록한 저장소 하나당 하나. 수신 경로는 {@code /api/alm/webhooks/{provider}/{publicId}}이고 인증은 서명이다.
 * 시크릿은 암호문({@code secretEnc})만 둔다 — 원문은 발급 응답에 한 번만 나간다.
 */
@Entity
@Table(name = "repository_webhook", uniqueConstraints = {
        @UniqueConstraint(name = "uk_repository_webhook_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_repository_webhook_repo", columnNames = {"project_id", "repository_url"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryWebhook {
    public static final String GITHUB = "GITHUB";
    public static final String GITLAB = "GITLAB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 40, updatable = false)
    private String publicId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "repository_url", nullable = false, length = 300, updatable = false)
    private String repositoryUrl;

    @Column(nullable = false, length = 10)
    private String provider;

    @Column(name = "secret_enc", nullable = false, length = 200)
    private String secretEnc;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "secret_rotated_at", nullable = false)
    private Instant secretRotatedAt;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "last_event", length = 40)
    private String lastEvent;

    @Column(name = "last_outcome", length = 20)
    private String lastOutcome;

    @Column(name = "last_message", length = 300)
    private String lastMessage;

    public static RepositoryWebhook create(String publicId, long projectId, String repositoryUrl, String provider,
                                           String secretEnc, long actorId, Instant now) {
        RepositoryWebhook hook = new RepositoryWebhook();
        hook.publicId = publicId;
        hook.projectId = projectId;
        hook.repositoryUrl = repositoryUrl;
        hook.provider = provider;
        hook.secretEnc = secretEnc;
        hook.actorId = actorId;
        hook.createdAt = now;
        hook.secretRotatedAt = now;
        return hook;
    }

    /** 시크릿 재발급 — 경로(publicId)는 그대로라 저장소 쪽 URL은 바꿀 필요 없고 시크릿만 새로 넣는다 */
    public void rotate(String provider, String secretEnc, long actorId, Instant now) {
        this.provider = provider;
        this.secretEnc = secretEnc;
        this.actorId = actorId;
        this.secretRotatedAt = now;
        this.lastDeliveryAt = null;
        this.lastEvent = null;
        this.lastOutcome = null;
        this.lastMessage = null;
    }
}
