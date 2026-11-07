package com.platform.almbackend.dev.scm;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 저장소 폴링 동기화(V45) 요청·응답 — 토큰 원문은 어디에도 싣지 않는다 */
public final class ScmSyncDtos {
    private ScmSyncDtos() {}

    @Schema(description = "폴링 동기화 등록·수정 — 같은 저장소면 고친다. token은 처음엔 필수, 이후엔 바꿀 때만")
    public record ScmSyncRequest(
            @Schema(description = "프로젝트에 등록된 저장소 URL", example = "https://github.com/chanho4702/alm-backend") String repositoryUrl,
            @Schema(description = "GITHUB · GITLAB — 비우면 github.com·gitlab.com만 짐작한다", example = "GITHUB") String provider,
            @Schema(description = "API 주소 — 비우면 기본값(github.com → https://api.github.com, GHES → <오리진>/api/v3, GitLab → <오리진>/api/v4). "
                    + "호스트는 저장소 호스트 또는 api.<호스트>만") String apiBaseUrl,
            @Schema(description = "읽기 토큰(PAT·GitHub App 설치 토큰·GitLab 프로젝트 토큰). 응답에는 다시 나오지 않는다") String token,
            @Schema(description = "이 저장소의 폴링을 켠다(기본 true)") Boolean enabled) {}

    @Schema(description = "저장소 폴링 동기화 상태")
    public record ScmSyncView(
            long id, String repositoryUrl,
            @Schema(description = "GITHUB · GITLAB") String provider,
            String apiBaseUrl, boolean enabled,
            @Schema(description = "수집 주체(토큰을 마지막으로 넣은 관리자)") long actorId,
            Instant createdAt, Instant tokenUpdatedAt,
            Instant lastRunAt, Instant lastSuccessAt,
            @Schema(description = "OK · RATE_LIMITED · FAILED") String lastOutcome,
            String lastMessage,
            Instant nextRunAt,
            @Schema(description = "레이트 리밋 대기 — 이 시각까지 돌지 않는다") Instant rateLimitedUntil,
            int failureCount,
            @Schema(description = "지금 어느 인스턴스가 돌리는 중") boolean running) {}

    @Schema(description = "프로젝트 동기화 설정 — 설치 스위치 ALM_SCM_SYNC와 저장소별 폴링")
    public record ScmSyncListResponse(
            @Schema(description = "webhook · polling · both") String syncMode,
            @Schema(description = "웹훅 수신을 받나(webhook·both)") boolean webhooksEnabled,
            @Schema(description = "폴링을 쓰나(polling·both)") boolean pollingEnabled,
            @Schema(description = "주기 실행이 이 설치에서 도나(스케줄러 꺼짐이면 '지금 동기화'만)") boolean schedulerActive,
            @Schema(description = "토큰 암호화 키(ALM_SETTINGS_ENC_KEY)가 있나") boolean encryptionConfigured,
            long pollIntervalSeconds,
            List<ScmSyncView> repositories) {}
}
