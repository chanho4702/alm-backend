package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이슈 배정에서 org 내부 API 호출까지 <b>진짜 HTTP로</b> 이어지는지 본다 — 예전 Mailpit 스모크가 SMTP로
 * 하던 역할을 플랫폼 메일 계약(2026-09-07)으로 옮긴 것이다. 여기서만 허브 대역이 아니라 실제
 * {@code OrgMailClient}가 쓰인다({@code platform.test.mail.fake=false}).
 *
 * <p>Mailpit·도커가 필요 없으므로 상시 테스트다 — 계약이 어긋나면(경로·토큰 헤더·본문 키) CI가 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PlatformMailStubSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpServer stub;
    private static final List<String> mailRequests = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> tokens = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void startStub(DynamicPropertyRegistry registry) {
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        stub.createContext("/internal/org/mail", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            mailRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"accepted\":1,\"disabled\":false}");
        });
        stub.createContext("/internal/org/mail/status", exchange ->
                respond(exchange, 200, "{\"enabled\":true}"));
        stub.start();
        registry.add("platform.org.internal.uri", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        registry.add("platform.org.internal.token", () -> "stub-internal-token");
        // 허브 대역 대신 진짜 클라이언트를 쓴다
        registry.add("platform.test.mail.fake", () -> "false");
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @Autowired WebApplicationContext context;
    @Autowired TestConfig.FakePermissionClient permissions;

    @Test
    void 배정_알림이_org_내부_API로_나간다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.setAllowed(true);

        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"stub\",\"name\":\"스텁\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();

        // Bob이 한 번 다녀가며 주소 스냅샷 + 스위치 on
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk());

        // Alice가 Bob에게 배정 → ASSIGNED 알림 + 메일
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"메일 스모크\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\","
                                + "\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{}}"))
                .andExpect(status().isCreated());

        String request = awaitMailRequest();
        assertThat(tokens).containsExactly("stub-internal-token");
        JsonNode mail = JSON.readTree(request);
        assertThat(mail.path("to").get(0).asText()).isEqualTo("bob@test.com");
        assertThat(mail.path("subject").asText()).startsWith("[ALM] ");
        assertThat(mail.path("text").asText())
                .contains("/projects/" + projectId + "/issues?issue=STUB-1");
        assertThat(mail.path("source").asText()).isEqualTo("alm");
    }

    /** 발송은 커밋 뒤 다른 스레드에서 일어난다 */
    private static String awaitMailRequest() throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!mailRequests.isEmpty()) return mailRequests.get(0);
            Thread.sleep(25);
        }
        throw new AssertionError("org 내부 메일 API 호출이 오지 않았다");
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
