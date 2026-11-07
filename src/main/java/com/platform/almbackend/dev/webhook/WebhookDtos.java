package com.platform.almbackend.dev.webhook;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 저장소 웹훅(V44) 요청·응답 */
public final class WebhookDtos {
    private WebhookDtos() {}

    @Schema(description = "웹훅 만들기·시크릿 재발급 — 같은 저장소에 이미 있으면 시크릿만 새로 만든다(페이로드 URL은 그대로)")
    public record WebhookRequest(
            @Schema(description = "프로젝트에 등록된 저장소 URL(정규화해 대조)", example = "https://github.com/chanho4702/alm-backend")
            String repositoryUrl,
            @Schema(description = "GITHUB · GITLAB", example = "GITHUB") String provider) {}

    @Schema(description = "최근 수신 한 건")
    public record DeliveryView(
            @Schema(description = "호스팅이 붙인 전달 id(X-GitHub-Delivery · Idempotency-Key)") String deliveryId,
            String event,
            @Schema(description = "PROCESSED · IGNORED · PING") String outcome,
            String message, Instant receivedAt) {}

    @Schema(description = "저장소 웹훅 — 시크릿 원문은 싣지 않는다")
    public record WebhookView(
            long id,
            String repositoryUrl,
            @Schema(description = "GITHUB · GITLAB") String provider,
            @Schema(description = "페이로드 경로 — 공개 주소 뒤에 붙인다", example = "/api/alm/webhooks/github/Q2h4...") String payloadPath,
            @Schema(description = "페이로드 전체 URL — 서버에 ALM_WEBHOOK_PUBLIC_URL이 있을 때만(없으면 화면이 자기 오리진으로 만든다)")
            String payloadUrl,
            @Schema(description = "이벤트를 처리할 때 권한을 쓰는 사람(시크릿을 마지막으로 발급한 관리자)") long actorId,
            Instant createdAt, Instant secretRotatedAt,
            Instant lastDeliveryAt, String lastEvent,
            @Schema(description = "PROCESSED · IGNORED · PING · REJECTED(서명·저장소 불일치) · FAILED(처리 실패 — 재전송하면 다시 처리)")
            String lastOutcome,
            String lastMessage,
            @Schema(description = "처리한 전달 최근 10건(최신 순)") List<DeliveryView> recentDeliveries) {}

    @Schema(description = "프로젝트 웹훅 목록")
    public record WebhookListResponse(
            @Schema(description = "서버에 시크릿 암호화 키(ALM_SETTINGS_ENC_KEY)가 있나 — 없으면 웹훅을 만들 수 없다") boolean encryptionConfigured,
            List<WebhookView> webhooks) {}

    @Schema(description = "발급 결과 — secret은 이 응답에서 한 번만 보인다")
    public record WebhookSecretResponse(WebhookView webhook,
                                        @Schema(description = "서명 시크릿 원문(16진 64자). 다시 볼 수 없다 — 잃으면 재발급") String secret) {}

    @Schema(description = "웹훅 수신 결과(호스팅에 돌려주는 본문)")
    public record ReceiveResponse(
            @Schema(description = "PROCESSED · IGNORED · PING · DUPLICATE") String outcome,
            String message) {}
}
