package com.platform.almbackend.dev.webhook;

import com.platform.almbackend.dev.webhook.WebhookDtos.ReceiveResponse;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookListResponse;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookRequest;
import com.platform.almbackend.dev.webhook.WebhookDtos.WebhookSecretResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * 저장소 웹훅(V44) — 관리(프로젝트 ADMIN, JWT)와 수신(공개, 서명 인증).
 *
 * <p>수신 경로 {@code POST /api/alm/webhooks/{github|gitlab}/{publicId}}는 JWT 없이 열린다(SecurityConfig·게이트웨이 permitAll).
 * 본문은 {@code platform.alm.webhook.max-body-bytes}(기본 5 MiB)까지만 읽는다 — 넘으면 413. Content type은 application/json만
 * (GitHub의 x-www-form-urlencoded 선택지는 415).
 */
@RestController
@Tag(name = "Development webhooks", description = "저장소 웹훅 — GitHub push·pull_request / GitLab Push·Merge Request를 받아 커밋·PR을 이슈에 잇는다")
public class WebhookController {
    private final RepositoryWebhookService webhooks;
    private final WebhookReceiver receiver;
    private final int maxBodyBytes;

    public WebhookController(RepositoryWebhookService webhooks, WebhookReceiver receiver,
                             @Value("${platform.alm.webhook.max-body-bytes:5242880}") int maxBodyBytes) {
        this.webhooks = webhooks;
        this.receiver = receiver;
        this.maxBodyBytes = maxBodyBytes;
    }

    // ── 관리 ──

    @Operation(summary = "프로젝트 저장소 웹훅 목록", description = "프로젝트 ADMIN. 시크릿 원문은 싣지 않는다")
    @GetMapping("/api/alm/projects/{projectId}/settings/webhooks")
    public WebhookListResponse list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return webhooks.list(userId(jwt), projectId);
    }

    @Operation(summary = "웹훅 만들기·시크릿 재발급",
            description = "프로젝트 ADMIN. 등록된 저장소만. 이미 있으면 시크릿만 새로 만든다(페이로드 URL 그대로). "
                    + "응답의 secret은 이때 한 번만 보인다. 서버에 ALM_SETTINGS_ENC_KEY가 없으면 400")
    @PostMapping("/api/alm/projects/{projectId}/settings/webhooks")
    public WebhookSecretResponse issue(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                       @RequestBody WebhookRequest request, @AuthenticationPrincipal Jwt jwt) {
        return webhooks.issue(userId(jwt), projectId, request);
    }

    @Operation(summary = "웹훅 삭제", description = "프로젝트 ADMIN. 이후 그 경로로 오는 전달은 404")
    @DeleteMapping("/api/alm/projects/{projectId}/settings/webhooks/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                       @Parameter(description = "웹훅 ID") @PathVariable long webhookId, @AuthenticationPrincipal Jwt jwt) {
        webhooks.delete(userId(jwt), projectId, webhookId);
    }

    // ── 수신 ──

    @Operation(summary = "웹훅 수신(GitHub·GitLab)",
            description = "JWT 없음 — GitHub는 X-Hub-Signature-256(HMAC-SHA256), GitLab은 X-Gitlab-Token으로 인증한다. "
                    + "push → 커밋 수집(V41 계약 그대로), pull_request·Merge Request → PR 웹 링크. 같은 전달 id 재전송은 DUPLICATE. "
                    + "서명 불일치 401 · 저장소 불일치 422 · 본문 초과 413 · org 불능 503(재전송하면 다시 처리)")
    @PostMapping("/api/alm/webhooks/{provider}/{publicId}")
    public ResponseEntity<ReceiveResponse> receive(@PathVariable String provider, @PathVariable String publicId,
                                                   HttpServletRequest request) throws IOException {
        String p = provider.toLowerCase(Locale.ROOT);
        if (!p.equals("github") && !p.equals("gitlab")) throw new com.platform.common.error.NotFoundException("웹훅을 찾을 수 없습니다");
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw new WebhookReceiver.RejectedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content type은 application/json이어야 합니다");
        }
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) throw tooLarge();
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(maxBodyBytes + 1);
        }
        if (body.length > maxBodyBytes) throw tooLarge();
        WebhookReceiver.Headers headers = p.equals("github")
                ? new WebhookReceiver.Headers(request.getHeader("X-GitHub-Event"), request.getHeader("X-Hub-Signature-256"),
                        null, request.getHeader("X-GitHub-Delivery"))
                : new WebhookReceiver.Headers(request.getHeader("X-Gitlab-Event"), null, request.getHeader("X-Gitlab-Token"),
                        firstNonBlank(request.getHeader("Idempotency-Key"), request.getHeader("X-Gitlab-Event-UUID")));
        return ResponseEntity.ok(receiver.receive(p, publicId, headers, body));
    }

    private WebhookReceiver.RejectedException tooLarge() {
        return new WebhookReceiver.RejectedException(HttpStatus.CONTENT_TOO_LARGE, "본문이 너무 큽니다(최대 " + maxBodyBytes + "바이트)");
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    @ExceptionHandler(WebhookReceiver.RejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(WebhookReceiver.RejectedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
