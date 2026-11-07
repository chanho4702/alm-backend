package com.platform.almbackend.dev.scm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ScmHttp 단위 — 본문을 멈춘 서버도 데드라인 안에 끊고(Codex P1), 본문 상한을 넘으면 읽기를 끊는다 */
class ScmHttpTest {
    HttpServer server;
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/stall", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0); // 청크 — 헤더만 보내고
            OutputStream out = ex.getResponseBody();
            out.write("[".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000); // 본문을 멈춘다
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] body = ("[" + "\"x\",".repeat(200) + "\"x\"]").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/ok", ex -> {
            byte[] body = "[1,2]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("ETag", "\"e1\"");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ScmHttp.Call call(Duration requestTimeout, int maxBytes) {
        ScmSyncSettings settings = new ScmSyncSettings("both", false, Duration.ofMinutes(5), Duration.ofHours(6), Duration.ofDays(14),
                Duration.ofSeconds(2), requestTimeout, maxBytes, 5, 30);
        return new ScmHttp(new ObjectMapper(), settings).open(base + "/api", Map.of());
    }

    @Test
    void 헤더만_보내고_본문을_멈추면_요청_데드라인에_끊는다() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> call(Duration.ofSeconds(1), 1024).get(URI.create(base + "/stall"), null))
                .isInstanceOf(ScmHttp.ScmException.class)
                .hasMessageContaining("응답 시간 초과");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void 본문_상한을_넘으면_끊고_정상_응답은_읽는다() {
        assertThatThrownBy(() -> call(Duration.ofSeconds(5), 100).get(URI.create(base + "/big"), null))
                .isInstanceOf(ScmHttp.ScmException.class)
                .hasMessage("Git 서버 응답이 너무 큽니다");
        ScmHttp.Response ok = call(Duration.ofSeconds(5), 100).get(URI.create(base + "/ok"), null);
        assertThat(ok.body().size()).isEqualTo(2);
        assertThat(ok.etag()).isEqualTo("\"e1\"");
    }
}
