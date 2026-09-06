package com.platform.almbackend.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 플랫폼 메일 허브(org-service) 클라이언트 — ALM은 더 이상 SMTP에 직접 붙지 않는다(2026-09-07 설계).
 *
 * <p>메일 설정·자격증명·재시도·발송 로그는 org 한 곳에 모이고, 소비자는 "이 주소들에게 이 내용을
 * 보내 달라"만 넘긴다. 그래서 보낸 사람 주소도 여기서 정하지 않는다 — org의 메일 설정이 정본이다.
 *
 * <p>사용자 JWT가 아니라 {@code X-Internal-Token}으로 인증하고, 게이트웨이가 라우팅하지 않는
 * {@code /internal/org/**}로 간다(auth-server {@code OrgInternalClient}와 같은 규약). 토큰이나 주소가
 * 비어 있으면 호출조차 하지 않는다 — org는 토큰이 비면 무조건 403이라 왕복이 헛돈다.
 *
 * <p><b>여기서는 던지지 않는다.</b> 알림 메일은 알림함의 사본이지 원본이 아니다 — org가 잠깐 안 뜬다고
 * 이슈 저장이나 발송 루프가 멈추면 안 된다({@code GrpcMemberDirectory}와 같은 이유). 실패는 warn 로그와
 * {@link SendResult}로만 남긴다.
 */
@Component
@Slf4j
public class OrgMailClient {

    /** 한 요청의 수신자 상한 — 계약이 정한 값이다(넘기면 org가 400). 넘치면 끊어 보낸다. */
    static final int MAX_RECIPIENTS = 100;

    /** 발송 여부는 관리 화면에서 바뀔 수 있다 — 개인 설정 조회마다 org를 부르지 않게 이만큼 캐시한다 */
    static final long STATUS_TTL_MILLIS = 60_000L;

    /** org 발송 로그의 출처 열에 그대로 뜬다 */
    private static final String SOURCE = "alm";

    private static final String HEADER = "X-Internal-Token";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String baseUri;
    private final String token;
    private final LongSupplier clock;

    /** 마지막으로 <b>성공한</b> status 조회. 실패는 이 값을 덮지 않는다. */
    private final AtomicReference<CachedStatus> status = new AtomicReference<>();

    private record CachedStatus(boolean enabled, long readAtMillis) {}

    @Autowired
    public OrgMailClient(@Value("${platform.org.internal.uri:}") String baseUri,
                         @Value("${platform.org.internal.token:}") String token) {
        this(baseUri, token, System::currentTimeMillis);
    }

    /** 캐시 만료를 검증하려면 시계를 밀어야 한다 — 테스트용 생성자 */
    OrgMailClient(String baseUri, String token, LongSupplier clock) {
        this.baseUri = trimTrailingSlash(baseUri);
        this.token = token == null ? "" : token.trim();
        this.clock = clock;
        // org가 멈추면 발송 스레드가 함께 잠긴다 — 예외는 삼켜도 행(hang)은 못 삼킨다.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** 주소와 토큰이 다 있어야 부를 수 있다 — 둘 중 하나라도 비면 org는 403만 준다 */
    public boolean configured() {
        return !baseUri.isEmpty() && !token.isEmpty();
    }

    /**
     * 플랫폼 메일이 켜져 있는가({@code GET /internal/org/mail/status}). 개인 설정 화면이 매번 묻기 때문에
     * 60초 캐시를 둔다.
     *
     * <p>조회에 실패하면 {@code false}를 주되 <b>캐시는 건드리지 않는다</b>. 실패를 캐시에 적으면 org가
     * 한 번 흔들린 대가로 60초 동안 "메일 꺼짐"이 되고, 그 사이 스위치를 켠 사람이 이유 없이 안내를 잃는다.
     */
    public boolean enabled() {
        if (!configured()) return false;
        long now = clock.getAsLong();
        CachedStatus cached = status.get();
        if (cached != null && now - cached.readAtMillis() < STATUS_TTL_MILLIS) return cached.enabled();
        try {
            HttpResponse<String> response = http.send(
                    get("/internal/org/mail/status"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("플랫폼 메일 상태 조회 실패: status={}", response.statusCode());
                return false;
            }
            boolean value = json.readTree(response.body()).path("enabled").asBoolean(false);
            status.set(new CachedStatus(value, now));
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("플랫폼 메일 상태를 읽지 못했다: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 발송 요청이 어떻게 끝났는가. <b>"꺼짐"과 "실패"를 가른다</b> — 관리자가 메일을 꺼 둔 것은 정상
     * 상태이고(경고할 일이 아니다), 허브가 답하지 않은 것은 사람이 봐야 할 일이다. 호출측은 둘 다
     * 예외 없이 넘어가되 로그 수준을 달리한다.
     */
    public enum SendResult {
        /** 202 — 허브의 발송 큐에 들어갔다 */
        ACCEPTED,
        /** 202 {@code {"accepted":0,"disabled":true}} — 관리자가 플랫폼 메일을 꺼 뒀다 */
        DISABLED,
        /**
         * 넘기지 못했다 — 그 밖의 응답·예외.
         *
         * <p>보낼 주소가 하나도 없거나 채널이 설정되지 않아 아예 부르지 못한 경우도 여기다. 그 둘은
         * <b>호출자가 먼저 걸러야 하는 상황</b>이고(주소 결정은 호출자 몫, 채널 설정은 {@link #configured()}로
         * 미리 볼 수 있다), 여기까지 왔다면 보내야 할 메일이 나가지 않은 것이므로 성공과 섞지 않는다.
         */
        FAILED;

        public boolean accepted() { return this == ACCEPTED; }

        public boolean failed() { return this == FAILED; }
    }

    public SendResult send(List<String> to, String subject, String text) {
        return send(to, subject, text, null);
    }

    /**
     * 한 통을 org 발송 큐에 넣는다. 수신자가 100을 넘으면 끊어서 여러 번 부른다 — 넘겨서 보내면
     * org가 400 {@code {"error"}}로 되돌린다(나누는 것은 소비자 몫이라는 계약이다).
     *
     * <p>한 묶음이라도 실패하면 결과는 {@link SendResult#FAILED}다 — 부분 성공을 성공으로 읽으면
     * 못 받은 사람이 보이지 않는다.
     */
    public SendResult send(List<String> to, String subject, String text, String html) {
        if (!configured()) return SendResult.FAILED;
        List<String> recipients = clean(to);
        if (recipients.isEmpty()) {
            log.warn("보낼 주소가 없어 메일 발송을 건너뛴다: subject={}", subject);
            return SendResult.FAILED;
        }
        SendResult result = null;
        for (int from = 0; from < recipients.size(); from += MAX_RECIPIENTS) {
            List<String> chunk = recipients.subList(from, Math.min(from + MAX_RECIPIENTS, recipients.size()));
            result = merge(result, post(chunk, subject, text, html));
        }
        return result;
    }

    /** 나눠 보낸 묶음들의 결과를 하나로 — 실패가 하나라도 있으면 실패이고, 전부 꺼짐일 때만 꺼짐이다 */
    private static SendResult merge(SendResult previous, SendResult next) {
        if (previous == null) return next;
        if (previous == SendResult.FAILED || next == SendResult.FAILED) return SendResult.FAILED;
        return previous == SendResult.DISABLED && next == SendResult.DISABLED
                ? SendResult.DISABLED
                : SendResult.ACCEPTED;
    }

    private SendResult post(List<String> to, String subject, String text, String html) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUri + "/internal/org/mail"))
                            .timeout(Duration.ofSeconds(5))
                            .header(HEADER, token)
                            .header("Content-Type", "application/json;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    body(to, subject, text, html), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 202) {
                log.warn("메일 발송 요청 거절: status={} recipients={}", response.statusCode(), to.size());
                return SendResult.FAILED;
            }
            // 허브가 받았지만 관리자가 메일을 꺼 둔 상태 — 실패가 아니라 설정이라 warn까지 올리지 않는다
            if (json.readTree(response.body()).path("disabled").asBoolean(false)) {
                log.debug("플랫폼 메일이 꺼져 있어 발송되지 않았다: subject={}", subject);
                return SendResult.DISABLED;
            }
            return SendResult.ACCEPTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        } catch (Exception e) {
            log.warn("메일 발송 요청 실패: recipients={} subject={}", to.size(), subject, e);
            return SendResult.FAILED;
        }
    }

    private String body(List<String> to, String subject, String text, String html) {
        ObjectNode node = json.createObjectNode();
        ArrayNode addresses = node.putArray("to");
        for (String address : to) addresses.add(address);
        node.put("subject", subject == null ? "" : subject);
        node.put("text", text == null ? "" : text);
        if (html != null && !html.isBlank()) node.put("html", html);
        node.put("source", SOURCE);
        return node.toString();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(Duration.ofSeconds(5))
                .header(HEADER, token)
                .GET()
                .build();
    }

    /** 빈 주소를 걷어내고 중복을 줄인다 — 같은 사람에게 같은 메일이 두 번 가지 않게 */
    private static List<String> clean(List<String> to) {
        if (to == null) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String address : to) {
            if (address != null && !address.isBlank()) unique.add(address.trim());
        }
        return new ArrayList<>(unique);
    }

    private static String trimTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
