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
 * 저장소 폴링 동기화(V45) — 등록 저장소 하나당 하나. 토큰은 암호문만, 커서·ETag는 증분 읽기용, lease_*는 다중 인스턴스 임대
 * ({@code RepositoryScmSyncRepository.claim*}의 조건부 UPDATE만 잡는다).
 */
@Entity
@Table(name = "repository_scm_sync",
        uniqueConstraints = @UniqueConstraint(name = "uk_repository_scm_sync_repo", columnNames = {"project_id", "repository_url"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryScmSync {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "repository_url", nullable = false, length = 300, updatable = false)
    private String repositoryUrl;

    @Column(nullable = false, length = 10)
    private String provider;

    @Column(name = "api_base_url", nullable = false, length = 300)
    private String apiBaseUrl;

    @Column(name = "token_enc", nullable = false, length = 2000)
    private String tokenEnc;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "token_updated_at", nullable = false)
    private Instant tokenUpdatedAt;

    @Column(name = "commits_cursor")
    private Instant commitsCursor;

    @Column(name = "commits_etag", length = 200)
    private String commitsEtag;

    @Column(name = "pulls_cursor")
    private Instant pullsCursor;

    @Column(name = "pulls_etag", length = 200)
    private String pullsEtag;

    /** PR/MR 키셋 커서의 동점 깨기(V47) — 마지막으로 처리한 PR 번호·MR iid */
    @Column(name = "pulls_cursor_number")
    private Long pullsCursorNumber;

    /** GitHub 이어 읽기 쪽 번호 힌트(V47) */
    @Column(name = "pulls_page")
    private Integer pullsPage;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "rate_limited_until")
    private Instant rateLimitedUntil;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_outcome", length = 20)
    private String lastOutcome;

    @Column(name = "last_message", length = 300)
    private String lastMessage;

    @Column(name = "lease_owner", length = 80)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    public static RepositoryScmSync create(long projectId, String repositoryUrl, String provider, String apiBaseUrl,
                                           String tokenEnc, long actorId, boolean enabled, Instant now) {
        RepositoryScmSync s = new RepositoryScmSync();
        s.projectId = projectId;
        s.repositoryUrl = repositoryUrl;
        s.provider = provider;
        s.apiBaseUrl = apiBaseUrl;
        s.tokenEnc = tokenEnc;
        s.actorId = actorId;
        s.enabled = enabled;
        s.createdAt = now;
        s.tokenUpdatedAt = now;
        s.nextRunAt = now;
        return s;
    }

    /** 대상(제공자·API 주소)이 바뀌면 커서를 버린다 — 다른 서버의 커서로 읽으면 빠뜨린다 */
    public void retarget(String provider, String apiBaseUrl, Instant now) {
        if (provider.equals(this.provider) && apiBaseUrl.equals(this.apiBaseUrl)) return;
        this.provider = provider;
        this.apiBaseUrl = apiBaseUrl;
        resetCursors();
        this.nextRunAt = now;
    }

    public void replaceToken(String tokenEnc, long actorId, Instant now) {
        this.tokenEnc = tokenEnc;
        this.actorId = actorId;
        this.tokenUpdatedAt = now;
        this.failureCount = 0;
        this.rateLimitedUntil = null;
        this.nextRunAt = now;
    }

    public void setEnabled(boolean enabled, Instant now) {
        if (enabled && !this.enabled) this.nextRunAt = now;
        this.enabled = enabled;
    }

    private void resetCursors() {
        this.commitsCursor = null;
        this.commitsEtag = null;
        this.pullsCursor = null;
        this.pullsEtag = null;
        this.pullsCursorNumber = null;
        this.pullsPage = null;
    }

    /** 성공 — 커서·ETag를 앞으로, 다음 실행은 주기 뒤(소진된 레이트 리밋이 있으면 그 뒤) */
    public void succeeded(Instant now, Instant nextRunAt, Instant rateLimitedUntil, Instant commitsCursor, String commitsEtag,
                          Instant pullsCursor, Long pullsCursorNumber, Integer pullsPage, String pullsEtag, String message) {
        this.commitsCursor = commitsCursor;
        this.commitsEtag = commitsEtag;
        this.pullsCursor = pullsCursor;
        this.pullsEtag = pullsEtag;
        this.pullsCursorNumber = pullsCursorNumber;
        this.pullsPage = pullsPage;
        this.failureCount = 0;
        this.rateLimitedUntil = rateLimitedUntil;
        this.lastRunAt = now;
        this.lastSuccessAt = now;
        this.lastOutcome = "OK";
        this.lastMessage = message;
        this.nextRunAt = nextRunAt;
        release();
    }

    public void rateLimited(Instant now, Instant until, String message) {
        this.rateLimitedUntil = until;
        this.lastRunAt = now;
        this.lastOutcome = "RATE_LIMITED";
        this.lastMessage = message;
        this.nextRunAt = until;
        release();
    }

    public void failed(Instant now, Instant nextRunAt, String message) {
        this.failureCount++;
        this.lastRunAt = now;
        this.lastOutcome = "FAILED";
        this.lastMessage = message;
        this.nextRunAt = nextRunAt;
        release();
    }

    private void release() {
        this.leaseOwner = null;
        this.leaseUntil = null;
    }
}
