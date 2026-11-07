package com.platform.almbackend.dev.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.dev.DevCommitService;
import com.platform.almbackend.dev.DevDtos.CommitInput;
import com.platform.almbackend.dev.DevDtos.CommitResult;
import com.platform.almbackend.dev.DevDtos.IngestRequest;
import com.platform.almbackend.dev.DevDtos.IngestResponse;
import com.platform.almbackend.dev.DevDtos.PullRequestLinkResult;
import com.platform.almbackend.dev.RepositoryUrls;
import com.platform.almbackend.dev.scm.ScmSyncSettings;
import com.platform.almbackend.dev.webhook.WebhookDtos.ReceiveResponse;
import com.platform.almbackend.dev.webhook.WebhookPayloads.Kind;
import com.platform.almbackend.dev.webhook.WebhookPayloads.Parsed;
import com.platform.almbackend.domain.RepositoryWebhook;
import com.platform.almbackend.repository.RepositoryWebhookDeliveryRepository;
import com.platform.almbackend.repository.RepositoryWebhookRepository;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 웹훅 수신(V44) — 인증(서명) → 저장소 대조 → 전달 멱등 → V41 커밋 수집·PR 웹 링크. JWT가 없는 공개 경로라 순서가 곧 방어다:
 * 서명을 확인하기 전에는 본문을 해석하지 않고, 서명이 맞아도 본문의 저장소가 웹훅의 저장소와 다르면 아무것도 하지 않는다
 * (한 저장소의 시크릿으로 다른 저장소 커밋을 밀어 넣지 못하게).
 *
 * <p><b>누구로 처리하나</b>: 웹훅을 마지막으로 발급한 관리자({@code actor_id})로 PAT 수집과 똑같이 판정한다 — 키마다 대상 프로젝트
 * EDIT, 없으면 그 키만 건너뛴다. org 불능은 삼키지 않는다: 503이고 전달 기록도 남지 않아 호스팅이 재전송하면 다시 처리된다.
 *
 * <p><b>멱등</b>: 전달 id(GitHub {@code X-GitHub-Delivery} — "Redeliver"도 같은 값, GitLab {@code Idempotency-Key}·
 * {@code X-Gitlab-Event-UUID})를 처리와 같은 트랜잭션에 넣는다. 처리에 성공한 전달만 남으므로 재전송은 DUPLICATE로 끝나고,
 * 실패한 전달의 재전송은 다시 처리된다. 아래 수집 자체도 (저장소, sha) 멱등이라 전달 id가 없거나 보존 기간을 넘겨도 중복은 생기지 않는다.
 */
@Service
@Slf4j
public class WebhookReceiver {
    public static final String PROCESSED = "PROCESSED";
    public static final String IGNORED = "IGNORED";
    public static final String PING = "PING";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    /** 웹훅마다 남기는 처리 전달 수 */
    public static final int KEEP_DELIVERIES = 100;

    /** 수신 헤더 — 호스팅마다 쓰는 것만 채운다 */
    public record Headers(String event, String signature256, String gitlabToken, String deliveryId) {}

    /** 인증·대조 실패 — 컨트롤러가 status와 {@code {"error"}}로 답한다 */
    public static final class RejectedException extends RuntimeException {
        private final HttpStatus status;

        RejectedException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private record Outcome(String outcome, String message) {}

    private final ObjectMapper json;
    private final RepositoryWebhookRepository webhooks;
    private final RepositoryWebhookDeliveryRepository deliveries;
    private final WebhookSecretCipher cipher;
    private final DevCommitService commits;
    private final ScmSyncSettings scm;
    private final TransactionTemplate tx;
    private final TransactionTemplate separateTx;

    public WebhookReceiver(ObjectMapper json, RepositoryWebhookRepository webhooks, RepositoryWebhookDeliveryRepository deliveries,
                           WebhookSecretCipher cipher, DevCommitService commits, ScmSyncSettings scm,
                           PlatformTransactionManager transactionManager) {
        this.json = json;
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.commits = commits;
        this.scm = scm;
        this.tx = new TransactionTemplate(transactionManager);
        this.separateTx = new TransactionTemplate(transactionManager);
        this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReceiveResponse receive(String provider, String publicId, Headers headers, byte[] body) {
        // 설치가 폴링만 쓰면(ALM_SCM_SYNC=polling) 수신 경로는 없는 것과 같다 — 서명 검증 전에 끊는다
        if (!scm.mode().webhooks()) throw new NotFoundException("이 설치는 웹훅 수신을 쓰지 않습니다");
        String providerKey = provider.toUpperCase(Locale.ROOT);
        RepositoryWebhook hook = webhooks.findByPublicId(publicId)
                .filter(w -> w.getProvider().equals(providerKey))
                .orElseThrow(() -> new NotFoundException("웹훅을 찾을 수 없습니다"));
        boolean gitHub = RepositoryWebhook.GITHUB.equals(providerKey);
        String eventName = headers.event() == null || headers.event().isBlank() ? "unknown" : WebhookPayloads.clip(headers.event().trim(), 40);

        // 1) 인증 — 본문은 아직 해석하지 않는다
        String secret;
        try {
            secret = cipher.decrypt(hook.getSecretEnc());
        } catch (IllegalStateException e) {
            recordLast(hook, eventName, FAILED, e.getMessage());
            log.error("웹훅 시크릿 복호화 실패: webhook={} {}", hook.getId(), e.getMessage());
            throw new ServiceUnavailableException(e.getMessage());
        }
        boolean verified = gitHub
                ? WebhookSignatures.verifyGitHub(secret, body, headers.signature256())
                : WebhookSignatures.verifyGitLab(secret, headers.gitlabToken());
        if (!verified) {
            String message = gitHub ? "서명(X-Hub-Signature-256)이 맞지 않습니다 — 저장소 웹훅의 Secret을 확인하세요"
                    : "토큰(X-Gitlab-Token)이 맞지 않습니다 — 저장소 웹훅의 Secret token을 확인하세요";
            reject(hook, eventName, HttpStatus.UNAUTHORIZED, message);
        }

        // 2) 해석·저장소 대조
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            root = null;
        }
        if (root == null || !root.isObject()) reject(hook, eventName, HttpStatus.BAD_REQUEST, "본문이 JSON 객체가 아닙니다 — Content type을 application/json으로 설정하세요");
        Parsed parsed = gitHub ? WebhookPayloads.gitHub(eventName, root) : WebhookPayloads.gitLab(eventName, root);
        if (parsed.repositoryUrl() != null || parsed.kind() == Kind.PUSH || parsed.kind() == Kind.PULL_REQUEST) {
            if (!sameRepository(hook.getRepositoryUrl(), parsed.repositoryUrl())) {
                reject(hook, eventName, HttpStatus.UNPROCESSABLE_ENTITY,
                        "이 웹훅에 등록된 저장소(" + hook.getRepositoryUrl() + ")의 이벤트가 아닙니다");
            }
        }

        String deliveryId = deliveryId(gitHub, headers.deliveryId());
        if (deliveryId == null) reject(hook, eventName, HttpStatus.BAD_REQUEST, "전달 id(X-GitHub-Delivery)가 없습니다");

        // 3) 처리 — 전달 기록과 수집이 한 트랜잭션. 실패하면 둘 다 남지 않고 마지막 상태만 FAILED로 따로 적는다
        try {
            ReceiveResponse response = tx.execute(status -> process(hook, parsed, deliveryId));
            log.info("웹훅 수신: webhook={} event={} delivery={} outcome={}", hook.getId(), eventName, deliveryId, response.outcome());
            return response;
        } catch (RuntimeException e) {
            String message = e instanceof ServiceUnavailableException || e instanceof IllegalArgumentException
                    ? e.getMessage() : "처리하지 못했습니다 — 서버 로그를 확인하세요";
            recordLast(hook, eventName, FAILED, message);
            log.error("웹훅 처리 실패: webhook={} event={} delivery={} {}", hook.getId(), eventName, deliveryId, e.toString());
            throw e;
        }
    }

    private ReceiveResponse process(RepositoryWebhook hook, Parsed parsed, String deliveryId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (deliveries.insertIfAbsent(hook.getId(), deliveryId, parsed.event(), "PROCESSING", null, now) == 0) {
            return new ReceiveResponse(DUPLICATE, "이미 처리한 전달입니다");
        }
        Outcome outcome = switch (parsed.kind()) {
            case PING -> new Outcome(PING, "연결 확인(ping)");
            case IGNORED -> new Outcome(IGNORED, parsed.ignoredReason());
            case PUSH -> push(hook, parsed);
            case PULL_REQUEST -> pullRequest(hook, parsed);
        };
        String message = outcome.message() == null ? null : WebhookPayloads.clip(outcome.message(), 300);
        deliveries.finish(hook.getId(), deliveryId, outcome.outcome(), message);
        webhooks.recordLast(hook.getId(), now, parsed.event(), outcome.outcome(), message);
        deliveries.idBeyond(hook.getId(), KEEP_DELIVERIES).ifPresent(maxId -> deliveries.deleteUpTo(hook.getId(), maxId));
        return new ReceiveResponse(outcome.outcome(), message);
    }

    private Outcome push(RepositoryWebhook hook, Parsed parsed) {
        List<CommitInput> all = parsed.commits();
        int stored = 0, duplicates = 0, skipped = 0, invalid = 0, links = 0, skippedKeys = 0;
        for (int from = 0; from < all.size(); from += DevCommitService.MAX_BATCH) {
            List<CommitInput> chunk = all.subList(from, Math.min(all.size(), from + DevCommitService.MAX_BATCH));
            IngestResponse r = commits.ingest(hook.getActorId(), new IngestRequest(hook.getRepositoryUrl(), parsed.branch(), chunk));
            stored += r.stored();
            duplicates += r.duplicates();
            skipped += r.skipped();
            invalid += r.invalid();
            links += r.linksCreated();
            for (CommitResult result : r.results()) skippedKeys += result.skipped().size();
        }
        StringBuilder message = new StringBuilder("커밋 ").append(all.size()).append("건 — 새로 저장 ").append(stored)
                .append(" · 새 연결 ").append(links);
        if (duplicates > 0) message.append(" · 이미 있음 ").append(duplicates);
        if (skipped > 0) message.append(" · 이을 이슈 없음 ").append(skipped);
        if (invalid > 0) message.append(" · 형식 오류 ").append(invalid);
        if (skippedKeys > 0) message.append(" · 건너뛴 키 ").append(skippedKeys);
        return new Outcome(PROCESSED, message.toString());
    }

    private Outcome pullRequest(RepositoryWebhook hook, Parsed parsed) {
        PullRequestLinkResult r = commits.linkPullRequest(hook.getActorId(), parsed.pullRequest().url(),
                parsed.pullRequest().title(), parsed.pullRequest().keyTexts(), parsed.pullRequest().state());
        StringBuilder message = new StringBuilder("PR — 새 연결 ").append(r.linked().size());
        if (!r.alreadyLinked().isEmpty()) message.append(" · 이미 연결 ").append(r.alreadyLinked().size());
        if (!r.skipped().isEmpty()) message.append(" · 건너뛴 키 ").append(r.skipped().size());
        if (r.linked().isEmpty() && r.alreadyLinked().isEmpty() && r.skipped().isEmpty()) message.append(" (제목·브랜치에 이슈 키 없음)");
        return new Outcome(PROCESSED, message.toString());
    }

    private static boolean sameRepository(String registered, String payloadUrl) {
        if (payloadUrl == null) return false;
        try {
            return registered.equals(RepositoryUrls.normalize(payloadUrl));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** GitHub는 필수. GitLab은 Idempotency-Key(17.4+)·X-Gitlab-Event-UUID 중 하나, 둘 다 없으면 임의 값(전달 멱등 없이 수집 멱등만) */
    private static String deliveryId(boolean gitHub, String raw) {
        if (raw != null && !raw.isBlank()) return WebhookPayloads.clip(raw.trim(), 100);
        return gitHub ? null : "local-" + UUID.randomUUID();
    }

    private void reject(RepositoryWebhook hook, String event, HttpStatus status, String message) {
        recordLast(hook, event, REJECTED, message);
        log.warn("웹훅 거부: webhook={} event={} status={} {}", hook.getId(), event, status.value(), message);
        throw new RejectedException(status, message);
    }

    private void recordLast(RepositoryWebhook hook, String event, String outcome, String message) {
        try {
            separateTx.executeWithoutResult(status -> webhooks.recordLast(hook.getId(), Instant.now().truncatedTo(ChronoUnit.MICROS),
                    event, outcome, message == null ? null : WebhookPayloads.clip(message, 300)));
        } catch (RuntimeException e) {
            // 상태 기록은 부가 정보다 — 기록 실패가 원래 응답(401·503 등)을 가리지 않게 한다
            log.warn("웹훅 상태 기록 실패: webhook={} {}", hook.getId(), e.toString());
        }
    }
}
