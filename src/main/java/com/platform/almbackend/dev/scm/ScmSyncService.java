package com.platform.almbackend.dev.scm;

import com.platform.almbackend.dev.RepositoryUrls;
import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncListResponse;
import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncRequest;
import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncView;
import com.platform.almbackend.dev.webhook.WebhookSecretCipher;
import com.platform.almbackend.domain.CodeRepository;
import com.platform.almbackend.domain.RepositoryScmSync;
import com.platform.almbackend.domain.RepositoryWebhook;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.CodeRepositoryRepository;
import com.platform.almbackend.repository.RepositoryScmSyncRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * 저장소 폴링 동기화 관리(V45) — 프로젝트 설정이라 전부 ADMIN. 등록된 저장소에만 만든다(등록에서 빠지면
 * {@code CodeRepositoryService.replace}가 함께 지운다). 토큰은 웹훅 시크릿과 같은 {@link WebhookSecretCipher}로 암호화해 두고
 * 어떤 응답에도 원문을 싣지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ScmSyncService {
    private static final int MAX_TOKEN = 1000;

    private final ProjectService projectService;
    private final CodeRepositoryRepository codeRepositories;
    private final RepositoryScmSyncRepository syncs;
    private final WebhookSecretCipher cipher;
    private final ScmSyncSettings settings;
    private final ScmSyncRunner runner;

    @Transactional(readOnly = true)
    public ScmSyncListResponse list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        Instant now = Instant.now();
        List<ScmSyncView> views = syncs.findByProjectIdOrderByIdAsc(projectId).stream().map(s -> view(s, now)).toList();
        return new ScmSyncListResponse(settings.mode().value(), settings.mode().webhooks(), settings.mode().polling(),
                settings.pollingActive(), cipher.isConfigured(), settings.pollInterval().toSeconds(), views);
    }

    public ScmSyncView upsert(long userId, long projectId, ScmSyncRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        if (!settings.mode().polling()) {
            throw new IllegalArgumentException("이 설치는 폴링 동기화를 쓰지 않습니다(ALM_SCM_SYNC=" + settings.mode().value() + ")");
        }
        if (request == null) throw new IllegalArgumentException("요청 본문이 필요합니다");
        String url = RepositoryUrls.normalize(request.repositoryUrl());
        boolean registered = codeRepositories.findByProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .map(CodeRepository::getUrl).anyMatch(url::equals);
        if (!registered) throw new IllegalArgumentException("먼저 저장소를 프로젝트에 등록하세요: " + url);

        String provider = request.provider() == null || request.provider().isBlank()
                ? ScmEndpoints.guessProvider(url) : request.provider().trim().toUpperCase(Locale.ROOT);
        if (provider == null) throw new IllegalArgumentException("이 저장소의 제공자(GITHUB·GITLAB)를 고르세요");
        if (!provider.equals(RepositoryWebhook.GITHUB) && !provider.equals(RepositoryWebhook.GITLAB)) {
            throw new IllegalArgumentException("제공자는 GITHUB 또는 GITLAB이어야 합니다");
        }
        String apiBase = request.apiBaseUrl() == null || request.apiBaseUrl().isBlank()
                ? ScmEndpoints.defaultApiBase(provider, url) : ScmEndpoints.validateApiBase(request.apiBaseUrl(), url);
        String token = request.token() == null ? null : request.token().trim();
        if (token != null && token.isEmpty()) token = null;
        if (token != null && (token.length() > MAX_TOKEN || token.chars().anyMatch(ch -> ch <= ' ' || ch == 0x7f))) {
            throw new IllegalArgumentException("토큰 형식이 올바르지 않습니다(공백·제어 문자 없이 " + MAX_TOKEN + "자 이하)");
        }
        if (token != null && !cipher.isConfigured()) {
            throw new IllegalArgumentException("토큰을 저장하려면 서버에 ALM_SETTINGS_ENC_KEY(32바이트 hex)가 필요합니다");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RepositoryScmSync sync = syncs.findByProjectIdAndRepositoryUrl(projectId, url).orElse(null);
        if (sync == null) {
            if (token == null) throw new IllegalArgumentException("읽기 토큰을 입력하세요");
            sync = syncs.save(RepositoryScmSync.create(projectId, url, provider, apiBase, cipher.encrypt(token), userId, enabled, now));
        } else {
            sync.retarget(provider, apiBase, now);
            if (token != null) sync.replaceToken(cipher.encrypt(token), userId, now);
            sync.setEnabled(enabled, now);
        }
        return view(sync, now);
    }

    public void delete(long userId, long projectId, long syncId) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        syncs.delete(owned(projectId, syncId));
    }

    /** "지금 동기화" — 트랜잭션 밖에서 돈다(외부 HTTP). 다른 실행 중·레이트 리밋 대기면 409 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScmSyncView runNow(long userId, long projectId, long syncId) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        if (!settings.mode().polling()) {
            throw new IllegalArgumentException("이 설치는 폴링 동기화를 쓰지 않습니다(ALM_SCM_SYNC=" + settings.mode().value() + ")");
        }
        owned(projectId, syncId);
        if (!runner.runNow(syncId)) {
            throw new ConflictException("이미 동기화 중이거나 Git 서버 레이트 리밋 대기 중입니다 — 잠시 뒤 다시 시도하세요");
        }
        return view(syncs.findById(syncId).orElseThrow(() -> new NotFoundException("동기화 설정을 찾을 수 없습니다")), Instant.now());
    }

    private RepositoryScmSync owned(long projectId, long syncId) {
        return syncs.findById(syncId).filter(s -> s.getProjectId() == projectId)
                .orElseThrow(() -> new NotFoundException("동기화 설정을 찾을 수 없습니다"));
    }

    private static ScmSyncView view(RepositoryScmSync s, Instant now) {
        return new ScmSyncView(s.getId() == null ? 0 : s.getId(), s.getRepositoryUrl(), s.getProvider(), s.getApiBaseUrl(), s.isEnabled(),
                s.getActorId(), s.getCreatedAt(), s.getTokenUpdatedAt(), s.getLastRunAt(), s.getLastSuccessAt(), s.getLastOutcome(),
                s.getLastMessage(), s.getNextRunAt(), s.getRateLimitedUntil(), s.getFailureCount(),
                s.getLeaseUntil() != null && s.getLeaseUntil().isAfter(now));
    }
}
