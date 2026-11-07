package com.platform.almbackend.dev.scm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 저장소 동기화 설정({@code platform.alm.scm.*}) — 한 곳에서 읽어 서비스·러너·웹훅이 같은 값을 본다 */
@Component
public class ScmSyncSettings {
    private final ScmSyncMode mode;
    private final boolean schedulerEnabled;
    private final Duration pollInterval;
    private final Duration maxBackoff;
    private final Duration initialLookback;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final int maxPages;
    private final int maxPullRequests;

    public ScmSyncSettings(@Value("${platform.alm.scm.sync:both}") String mode,
                           @Value("${platform.alm.scm.scheduler-enabled:true}") boolean schedulerEnabled,
                           @Value("${platform.alm.scm.poll-interval:5m}") Duration pollInterval,
                           @Value("${platform.alm.scm.max-backoff:6h}") Duration maxBackoff,
                           @Value("${platform.alm.scm.initial-lookback:14d}") Duration initialLookback,
                           @Value("${platform.alm.scm.connect-timeout:5s}") Duration connectTimeout,
                           @Value("${platform.alm.scm.request-timeout:20s}") Duration requestTimeout,
                           @Value("${platform.alm.scm.max-response-bytes:5242880}") int maxResponseBytes,
                           @Value("${platform.alm.scm.max-pages:5}") int maxPages,
                           @Value("${platform.alm.scm.max-pull-requests:30}") int maxPullRequests) {
        this.mode = ScmSyncMode.parse(mode);
        this.schedulerEnabled = schedulerEnabled;
        if (pollInterval.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException("ALM_SCM_POLL_INTERVAL은 1분 이상이어야 합니다(Git 서버 레이트 리밋 보호)");
        }
        this.pollInterval = pollInterval;
        this.maxBackoff = maxBackoff;
        this.initialLookback = initialLookback;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.maxPages = Math.max(1, maxPages);
        this.maxPullRequests = Math.max(1, maxPullRequests);
    }

    public ScmSyncMode mode() { return mode; }
    /** 주기 실행이 실제로 도나 — 모드가 폴링을 포함하고 스케줄러가 켜져 있을 때 */
    public boolean pollingActive() { return mode.polling() && schedulerEnabled; }
    public boolean schedulerEnabled() { return schedulerEnabled; }
    public Duration pollInterval() { return pollInterval; }
    public Duration maxBackoff() { return maxBackoff; }
    public Duration initialLookback() { return initialLookback; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public int maxResponseBytes() { return maxResponseBytes; }
    public int maxPages() { return maxPages; }
    public int maxPullRequests() { return maxPullRequests; }
}
