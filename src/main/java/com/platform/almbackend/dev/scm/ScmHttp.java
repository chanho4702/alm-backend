package com.platform.almbackend.dev.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Git 서버 API 호출 — 폴링 전용 HTTP. 서버가 외부로 요청을 보내는 유일한 통로라 가드를 여기 모은다.
 *
 * <ul>
 *   <li><b>대상 고정</b>: 요청 URI의 오리진이 그 저장소의 API 오리진({@link ScmEndpoints#allowedOrigin})과 같을 때만 보낸다 —
 *       응답의 다음 쪽 링크(Link·X-Next-Page)도 같은 검사를 거친다.</li>
 *   <li><b>리다이렉트 안 따라감</b>(NEVER) — 3xx는 오류. 허용 호스트에서 내부 주소로 튕기는 우회를 막는다.</li>
 *   <li>연결·요청 타임아웃, 응답 본문 상한(넘으면 오류 — 끝없이 읽지 않는다).</li>
 *   <li><b>레이트 리밋</b>: 429·(403 + 남은 수 0)은 {@link RateLimitedException}(Retry-After 또는 reset 시각까지), 성공이어도 남은 수가
 *       0이면 그 reset 시각을 {@link Call#exhaustedUntil()}로 알려 다음 실행을 미룬다. GitHub {@code x-ratelimit-*}·GitLab {@code ratelimit-*}.</li>
 * </ul>
 */
@Component
public class ScmHttp {

    /** 사용자에게 그대로 보이는 실패(마지막 동기화 결과 문구) */
    public static class ScmException extends RuntimeException {
        public ScmException(String message) {
            super(message);
        }
    }

    public static final class RateLimitedException extends ScmException {
        private final Instant until;

        public RateLimitedException(Instant until) {
            super("Git 서버 레이트 리밋 — " + until + "까지 기다립니다");
            this.until = until;
        }

        public Instant until() {
            return until;
        }
    }

    /** 한 번의 실행 동안 같은 대상·토큰으로 부르는 묶음. 레이트 리밋 소진 시각을 모은다 */
    public final class Call {
        private final String allowedOrigin;
        private final Map<String, String> authHeaders;
        private Instant exhaustedUntil;

        Call(String allowedOrigin, Map<String, String> authHeaders) {
            this.allowedOrigin = allowedOrigin;
            this.authHeaders = authHeaders;
        }

        public Instant exhaustedUntil() {
            return exhaustedUntil;
        }

        /** @param etag If-None-Match로 보낼 값(없으면 null) — 304면 {@link Response#notModified()} */
        public Response get(URI uri, String etag) {
            guard(uri);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(settings.requestTimeout()).GET()
                    .header("User-Agent", "platform-alm-scm-sync");
            authHeaders.forEach(builder::header);
            if (etag != null && !etag.isBlank()) builder.header("If-None-Match", etag);
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new ScmException("Git 서버 응답 시간 초과(" + uri.getHost() + ")");
            } catch (IOException e) {
                throw new ScmException("Git 서버에 연결할 수 없습니다(" + uri.getHost() + ") — " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScmException("동기화가 중단되었습니다");
            }
            int status = response.statusCode();
            Optional<Long> remaining = longHeader(response, "x-ratelimit-remaining").or(() -> longHeader(response, "ratelimit-remaining"));
            Optional<Long> reset = longHeader(response, "x-ratelimit-reset").or(() -> longHeader(response, "ratelimit-reset"));
            Optional<Long> retryAfter = longHeader(response, "retry-after");
            try (InputStream in = response.body()) {
                if (status == 429 || (status == 403 && (retryAfter.isPresent() || remaining.orElse(1L) == 0L))) {
                    throw new RateLimitedException(retryAfter.map(s -> Instant.now().plusSeconds(Math.max(1, s)))
                            .or(() -> reset.map(Instant::ofEpochSecond))
                            .orElse(Instant.now().plusSeconds(60)));
                }
                if (remaining.orElse(1L) == 0L) {
                    exhaustedUntil = reset.map(Instant::ofEpochSecond).orElse(Instant.now().plusSeconds(60));
                }
                if (status == 304) return new Response(304, null, etagOf(response), response.headers().map());
                if (status >= 300 && status < 400) throw new ScmException("Git 서버가 다른 주소로 보냈습니다(" + status + ") — 리다이렉트는 따라가지 않습니다. API 주소를 확인하세요");
                if (status == 401) throw new ScmException("토큰이 거부되었습니다(401) — 토큰을 확인하거나 다시 넣으세요");
                if (status == 403) throw new ScmException("권한이 없습니다(403) — 토큰에 저장소 읽기 권한이 있는지 확인하세요");
                if (status == 404) throw new ScmException("저장소를 찾을 수 없습니다(404) — 저장소 URL·API 주소·토큰 권한을 확인하세요");
                if (status >= 400) throw new ScmException("Git 서버 오류(" + status + ")");
                byte[] bytes = in.readNBytes(settings.maxResponseBytes() + 1);
                if (bytes.length > settings.maxResponseBytes()) throw new ScmException("Git 서버 응답이 너무 큽니다");
                JsonNode body;
                try {
                    body = json.readTree(bytes);
                } catch (IOException e) {
                    throw new ScmException("Git 서버 응답이 JSON이 아닙니다 — API 주소를 확인하세요");
                }
                return new Response(status, body, etagOf(response), response.headers().map());
            } catch (IOException e) {
                throw new ScmException("Git 서버 응답을 읽지 못했습니다");
            }
        }

        private void guard(URI uri) {
            String origin;
            try {
                origin = ScmEndpoints.allowedOrigin(uri.toString());
            } catch (RuntimeException e) {
                throw new ScmException("허용되지 않은 주소입니다");
            }
            if (uri.getRawUserInfo() != null || !origin.equals(allowedOrigin)) {
                throw new ScmException("허용되지 않은 호스트로 요청하지 않습니다(" + uri.getHost() + ")");
            }
        }
    }

    public record Response(int status, JsonNode body, String etag, Map<String, java.util.List<String>> headers) {
        public boolean notModified() {
            return status == 304;
        }

        public String header(String name) {
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().getFirst();
            }
            return null;
        }
    }

    private final ObjectMapper json;
    private final ScmSyncSettings settings;
    private final HttpClient client;

    public ScmHttp(ObjectMapper json, ScmSyncSettings settings) {
        this.json = json;
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.connectTimeout())
                .build();
    }

    public Call open(String apiBase, Map<String, String> authHeaders) {
        return new Call(ScmEndpoints.allowedOrigin(apiBase), authHeaders);
    }

    private static String etagOf(HttpResponse<?> response) {
        String etag = response.headers().firstValue("etag").orElse(null);
        return etag != null && etag.length() <= 200 ? etag : null;
    }

    private static Optional<Long> longHeader(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }
}
