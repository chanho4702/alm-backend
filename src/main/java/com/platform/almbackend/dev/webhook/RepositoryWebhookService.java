package com.platform.almbackend.dev.webhook;

import com.platform.almbackend.dev.RepositoryUrls;
import com.platform.almbackend.dev.scm.ScmSyncSettings;
import com.platform.almbackend.dev.webhook.WebhookDtos.DeliveryView;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookListResponse;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookRequest;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookSecretResponse;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookView;
import com.platform.almbackend.domain.CodeRepository;
import com.platform.almbackend.domain.RepositoryWebhook;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.CodeRepositoryRepository;
import com.platform.almbackend.repository.RepositoryWebhookDeliveryRepository;
import com.platform.almbackend.repository.RepositoryWebhookRepository;
import com.platform.common.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 저장소 웹훅 관리(V44) — 프로젝트 설정이라 읽기·쓰기 모두 ADMIN이다(최근 수신 기록에 저장소 활동이 보인다).
 * 웹훅은 <b>등록된 저장소에만</b> 만든다 — 등록에서 빠지면 {@code CodeRepositoryService.replace}가 함께 지운다.
 */
@Service
@Transactional
public class RepositoryWebhookService {
    public static final int RECENT_DELIVERIES = 10;

    private final ProjectService projectService;
    private final CodeRepositoryRepository codeRepositories;
    private final RepositoryWebhookRepository webhooks;
    private final RepositoryWebhookDeliveryRepository deliveries;
    private final WebhookSecretCipher cipher;
    private final ScmSyncSettings scm;
    private final String publicBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public RepositoryWebhookService(ProjectService projectService, CodeRepositoryRepository codeRepositories,
                                    RepositoryWebhookRepository webhooks, RepositoryWebhookDeliveryRepository deliveries,
                                    WebhookSecretCipher cipher, ScmSyncSettings scm,
                                    @Value("${platform.alm.webhook.public-base-url:}") String publicBaseUrl) {
        this.projectService = projectService;
        this.codeRepositories = codeRepositories;
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.scm = scm;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.publicBaseUrl = base;
    }

    @Transactional(readOnly = true)
    public WebhookListResponse list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        List<WebhookView> views = webhooks.findByProjectIdOrderByIdAsc(projectId).stream().map(this::view).toList();
        return new WebhookListResponse(cipher.isConfigured(), scm.mode().webhooks(), views);
    }

    /** 만들기·재발급 — 시크릿 원문은 이 응답에만 실린다 */
    public WebhookSecretResponse issue(long userId, long projectId, WebhookRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        if (!scm.mode().webhooks()) {
            throw new IllegalArgumentException("이 설치는 웹훅 수신을 쓰지 않습니다(ALM_SCM_SYNC=" + scm.mode().value() + ") — 폴링 동기화를 쓰세요");
        }
        if (request == null) throw new IllegalArgumentException("요청 본문이 필요합니다");
        String provider = provider(request.provider());
        String url = RepositoryUrls.normalize(request.repositoryUrl());
        boolean registered = codeRepositories.findByProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .map(CodeRepository::getUrl).anyMatch(url::equals);
        if (!registered) throw new IllegalArgumentException("먼저 저장소를 프로젝트에 등록하세요: " + url);
        if (!cipher.isConfigured()) {
            throw new IllegalArgumentException("웹훅 시크릿을 저장하려면 서버에 ALM_SETTINGS_ENC_KEY(32바이트 hex)가 필요합니다");
        }
        String secret = HexFormat.of().formatHex(bytes(32));
        String sealed = cipher.encrypt(secret);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RepositoryWebhook hook = webhooks.findByProjectIdAndRepositoryUrl(projectId, url).orElse(null);
        if (hook == null) {
            hook = webhooks.save(RepositoryWebhook.create(publicId(), projectId, url, provider, sealed, userId, now));
        } else {
            hook.rotate(provider, sealed, userId, now);
        }
        return new WebhookSecretResponse(view(hook), secret);
    }

    public void delete(long userId, long projectId, long webhookId) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        RepositoryWebhook hook = webhooks.findById(webhookId)
                .filter(w -> w.getProjectId() == projectId)
                .orElseThrow(() -> new NotFoundException("웹훅을 찾을 수 없습니다"));
        webhooks.delete(hook);
    }

    private WebhookView view(RepositoryWebhook w) {
        String path = payloadPath(w);
        List<DeliveryView> recent = w.getId() == null ? List.of()
                : deliveries.findByWebhookIdOrderByIdDesc(w.getId(), Limit.of(RECENT_DELIVERIES)).stream()
                .map(d -> new DeliveryView(d.getDeliveryId(), d.getEvent(), d.getOutcome(), d.getMessage(), d.getReceivedAt()))
                .toList();
        return new WebhookView(w.getId(), w.getRepositoryUrl(), w.getProvider(), path,
                publicBaseUrl.isEmpty() ? null : publicBaseUrl + path, w.getActorId(), w.getCreatedAt(), w.getSecretRotatedAt(),
                w.getLastDeliveryAt(), w.getLastEvent(), w.getLastOutcome(), w.getLastMessage(), recent);
    }

    public static String payloadPath(RepositoryWebhook w) {
        return "/api/alm/webhooks/" + w.getProvider().toLowerCase(Locale.ROOT) + "/" + w.getPublicId();
    }

    private static String provider(String raw) {
        String value = raw == null || raw.isBlank() ? RepositoryWebhook.GITHUB : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.equals(RepositoryWebhook.GITHUB) && !value.equals(RepositoryWebhook.GITLAB)) {
            throw new IllegalArgumentException("웹훅 제공자는 GITHUB 또는 GITLAB이어야 합니다");
        }
        return value;
    }

    /** 경로용 무작위 id — 24바이트(base64url 32자). 추측으로 남의 웹훅 경로를 찾지 못하게(인증은 여전히 서명) */
    private String publicId() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(24));
    }

    private byte[] bytes(int n) {
        byte[] out = new byte[n];
        random.nextBytes(out);
        return out;
    }
}
