package com.platform.almbackend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.notification.OrgMailClient.SendResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랫폼 메일 허브 클라이언트 — 진짜 HTTP로 검증한다. 목으로 대신하면 정작 계약(경로·헤더·본문 모양·
 * 응답 판정)은 하나도 확인하지 못한다. org-service 자리에 로컬 {@link HttpServer} 스텁을 세운다.
 *
 * <p>스텁은 org가 확정한 계약(2026-09-07) 그대로 답한다: 받아들이면 202 {@code {"accepted":n,
 * "disabled":false}}, 관리자가 꺼 뒀으면 202 {@code {"accepted":0,"disabled":true}}, 수신자가 100을
 * 넘으면 400 {@code {"error"}}. 마지막 것은 스텁이 <b>강제</b>한다 — 나누는 책임이 소비자에게 있으므로,
 * 분할이 깨지면 여기서 400으로 드러나야 한다.
 */
class OrgMailClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUri;

    /** 스텁이 받은 요청들 — 경로·토큰 헤더·본문 */
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    private record Received(String path, String token, String body) {}

    /** 다음 응답 — 테스트가 갈아 끼운다 */
    private volatile int mailStatusCode = 202;
    private volatile String mailBody = "{\"accepted\":1,\"disabled\":false}";
    private volatile int statusStatusCode = 200;
    private volatile String statusBody = "{\"enabled\":true}";
    private volatile long handlerDelayMillis;
    /** 테스트가 끝나면 미루던 핸들러도 곧바로 손을 뗀다 */
    private volatile boolean stopped;

    /** 시계는 테스트가 민다 — 60초 캐시를 진짜로 기다리지 않는다 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 응답을 미루는 테스트가 서버 스레드를 붙잡고 JVM 종료를 늦추지 않게 데몬 풀에서 돌린다
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mail-stub");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/internal/org/mail", exchange -> {
            String body = capture(exchange);
            // 상한을 넘겨 보내면 org는 400이다 — 스텁도 같이 거절한다
            if (recipientCount(body) > OrgMailClient.MAX_RECIPIENTS) {
                respond(exchange, 400, "{\"error\":\"받는 사람은 100명까지입니다\"}");
                return;
            }
            respond(exchange, mailStatusCode, mailBody);
        });
        server.createContext("/internal/org/mail/status", exchange -> {
            capture(exchange);
            respond(exchange, statusStatusCode, statusBody);
        });
        server.start();
        baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        stopped = true;
        server.stop(0);
    }

    private OrgMailClient client() {
        return new OrgMailClient(baseUri, "secret-token", now::get);
    }

    @Test
    void 허브가_202를_주면_보낸_것으로_본다() throws Exception {
        SendResult result = client().send(List.of("bob@org.example"), "[ALM] 배정", "본문");

        assertThat(result).isEqualTo(SendResult.ACCEPTED);
        assertThat(received).hasSize(1);
        Received request = received.get(0);
        assertThat(request.path()).isEqualTo("/internal/org/mail");
        // 사용자 JWT가 아니라 내부 토큰으로 인증한다 — 헤더 이름과 값이 계약이다
        assertThat(request.token()).isEqualTo("secret-token");
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.path("to")).hasSize(1);
        assertThat(body.path("to").get(0).asText()).isEqualTo("bob@org.example");
        assertThat(body.path("subject").asText()).isEqualTo("[ALM] 배정");
        assertThat(body.path("text").asText()).isEqualTo("본문");
        // 발송 로그의 출처 열에 그대로 뜬다
        assertThat(body.path("source").asText()).isEqualTo("alm");
        // 텍스트만 있는 메일은 html 키를 아예 싣지 않는다
        assertThat(body.has("html")).isFalse();
    }

    @Test
    void html을_주면_함께_싣는다() throws Exception {
        client().send(List.of("bob@org.example"), "제목", "본문", "<p>본문</p>");

        JsonNode body = JSON.readTree(received.get(0).body());
        assertThat(body.path("html").asText()).isEqualTo("<p>본문</p>");
    }

    /**
     * 관리자가 메일을 꺼 둔 상태 — 허브가 받아 준 것이므로 실패가 아니지만, 나가지도 않았으므로
     * "보냈다"도 아니다. 호출측이 로그 수준을 가를 수 있게 따로 알린다.
     */
    @Test
    void 메일이_꺼져_있으면_실패가_아니라_꺼짐이다() {
        mailBody = "{\"accepted\":0,\"disabled\":true}";

        assertThat(client().send(List.of("bob@org.example"), "제목", "본문"))
                .isEqualTo(SendResult.DISABLED);
    }

    /** 빈 주소는 서버가 걷어내고 accepted에 세지 않는다 — 그래도 허브가 받은 요청이다 */
    @Test
    void 받은_주소가_0건이어도_꺼진_것은_아니다() {
        mailBody = "{\"accepted\":0,\"disabled\":false}";

        assertThat(client().send(List.of("bob@org.example"), "제목", "본문"))
                .isEqualTo(SendResult.ACCEPTED);
    }

    @Test
    void 허브가_오류를_주면_실패다() {
        mailStatusCode = 500;
        mailBody = "{\"error\":\"메일 설정을 읽지 못했습니다\"}";

        assertThat(client().send(List.of("bob@org.example"), "제목", "본문"))
                .isEqualTo(SendResult.FAILED);
    }

    /** org가 멈추면 발송 스레드가 함께 잠긴다 — 읽기 5초에서 끊고 실패로 돌려준다 */
    @Test
    void 응답이_없으면_기다리다_실패한다() {
        handlerDelayMillis = 8_000;

        long startedAt = System.nanoTime();
        SendResult result = client().send(List.of("bob@org.example"), "제목", "본문");
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result).isEqualTo(SendResult.FAILED);
        // 읽기 타임아웃(5초)에서 끊었지, 다른 이유로 곧장 실패한 것이 아니다
        assertThat(elapsedMillis).isBetween(4_000L, 7_500L);
    }

    /**
     * 한 요청의 수신자 상한은 100이고, 나누는 것은 <b>소비자 몫</b>이다(넘기면 org가 400).
     * 스텁이 그 400을 실제로 던지므로, 분할이 깨지면 결과가 FAILED로 바뀌어 드러난다.
     */
    @Test
    void 수신자가_상한을_넘으면_나눠_부른다() throws Exception {
        List<String> many = IntStream.range(0, 250).mapToObj(i -> "user" + i + "@org.example").toList();

        assertThat(client().send(many, "제목", "본문")).isEqualTo(SendResult.ACCEPTED);

        assertThat(received).hasSize(3);
        List<Integer> sizes = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (Received request : received) {
            JsonNode to = JSON.readTree(request.body()).path("to");
            sizes.add(to.size());
            to.forEach(node -> all.add(node.asText()));
        }
        assertThat(sizes).containsExactly(OrgMailClient.MAX_RECIPIENTS, OrgMailClient.MAX_RECIPIENTS, 50);
        // 나누다가 빠뜨린 사람이 없어야 한다
        assertThat(all).containsExactlyElementsOf(many);
    }

    /** 한 묶음이라도 실패하면 전체가 실패다 — 부분 성공을 성공으로 읽으면 못 받은 사람이 안 보인다 */
    @Test
    void 나눠_보낸_한_묶음이_실패하면_실패다() {
        mailStatusCode = 500;
        mailBody = "{\"error\":\"메일 서버에 연결하지 못했습니다\"}";
        List<String> many = IntStream.range(0, 150).mapToObj(i -> "user" + i + "@org.example").toList();

        assertThat(client().send(many, "제목", "본문")).isEqualTo(SendResult.FAILED);
    }

    /** 서버도 빈 주소를 걷어내지만 여기서 먼저 턴다 — 빈 자리만 실은 요청을 보내지 않는다 */
    @Test
    void 빈_주소는_실어_보내지_않는다() throws Exception {
        List<String> mixed = new ArrayList<>();
        mixed.add("");
        mixed.add("  bob@org.example  ");
        mixed.add(null);
        mixed.add("   ");

        assertThat(client().send(mixed, "제목", "본문")).isEqualTo(SendResult.ACCEPTED);

        assertThat(received).hasSize(1);
        JsonNode to = JSON.readTree(received.get(0).body()).path("to");
        assertThat(to).hasSize(1);
        assertThat(to.get(0).asText()).isEqualTo("bob@org.example");
    }

    @Test
    void 보낼_주소가_하나도_없으면_부르지_않는다() {
        List<String> blanks = new ArrayList<>();
        blanks.add("");
        blanks.add("   ");
        blanks.add(null);

        assertThat(client().send(blanks, "제목", "본문")).isEqualTo(SendResult.FAILED);
        assertThat(received).isEmpty();
    }

    /** 개인 설정 화면이 열릴 때마다 묻기 때문에 60초는 캐시한다 */
    @Test
    void 메일_사용_여부는_60초_캐시된다() {
        OrgMailClient client = client();

        assertThat(client.enabled()).isTrue();
        assertThat(client.enabled()).isTrue();
        assertThat(received).hasSize(1);

        // 캐시가 살아 있는 동안에는 허브의 답이 바뀌어도 그대로다
        statusBody = "{\"enabled\":false}";
        now.addAndGet(OrgMailClient.STATUS_TTL_MILLIS - 1);
        assertThat(client.enabled()).isTrue();
        assertThat(received).hasSize(1);

        now.addAndGet(1);
        assertThat(client.enabled()).isFalse();
        assertThat(received).hasSize(2);
    }

    /**
     * 실패를 캐시에 적으면 org가 한 번 흔들린 대가로 60초 동안 "메일 꺼짐"이 된다 — 실패는 그 자리에서만
     * false이고, 다음 호출은 캐시를 믿지 않고 다시 물어본다.
     */
    @Test
    void 상태_조회에_실패해도_캐시를_더럽히지_않는다() {
        OrgMailClient client = client();
        assertThat(client.enabled()).isTrue();

        statusStatusCode = 503;
        statusBody = "{\"error\":\"메일 설정을 읽지 못했습니다\"}";
        now.addAndGet(OrgMailClient.STATUS_TTL_MILLIS);
        assertThat(client.enabled()).isFalse();
        assertThat(received).hasSize(2);

        // 실패한 답이 캐시에 눌러앉지 않았으므로 곧바로 다시 묻는다
        assertThat(client.enabled()).isFalse();
        assertThat(received).hasSize(3);

        // 허브가 돌아오면 같은 자리에서 회복한다
        statusStatusCode = 200;
        statusBody = "{\"enabled\":true}";
        assertThat(client.enabled()).isTrue();
    }

    /** 토큰이 없으면 org는 무조건 403이다 — 왕복하지 않고 메일이 꺼진 것으로 본다 */
    @Test
    void 토큰이_없으면_부르지_않는다() {
        OrgMailClient client = new OrgMailClient(baseUri, "  ", now::get);

        assertThat(client.configured()).isFalse();
        assertThat(client.enabled()).isFalse();
        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isEqualTo(SendResult.FAILED);
        assertThat(received).isEmpty();
    }

    @Test
    void 주소가_없으면_부르지_않는다() {
        OrgMailClient client = new OrgMailClient("", "secret-token", now::get);

        assertThat(client.configured()).isFalse();
        assertThat(client.enabled()).isFalse();
        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isEqualTo(SendResult.FAILED);
        assertThat(received).isEmpty();
    }

    private String capture(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Internal-Token"), body));
        return body;
    }

    private static int recipientCount(String body) {
        try {
            return JSON.readTree(body).path("to").size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        long until = System.nanoTime() + handlerDelayMillis * 1_000_000L;
        while (!stopped && System.nanoTime() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
