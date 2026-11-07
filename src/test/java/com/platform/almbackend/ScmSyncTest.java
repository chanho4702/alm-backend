package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.dev.scm.ScmSyncRunner;
import com.platform.almbackend.dev.webhook.WebhookSignatures;
import com.platform.almbackend.permission.AlmAction;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 폴링 동기화(V45) — 실제 HTTP로 가짜 GitHub(Enterprise 경로)·GitLab을 띄워 증분(ETag 304)·PR 상태·열린 PR 커밋·웹훅과의 멱등·
 * 레이트 리밋·백오프·SSRF 가드(호스트 고정·리다이렉트·다음 쪽 링크)·임대·토큰 비노출을 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ScmSyncTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ADMIN = 1;
    private static final String TOKEN = "ghp_testTokenValue123";

    /** 가짜 Git 서버 — 경로(raw) → 응답. 요청은 기록한다 */
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(String body, String... headers) {
            Map<String, String> h = new java.util.HashMap<>();
            for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]);
            return new Reply(200, body, h);
        }
    }

    record Seen(String path, String query, Map<String, String> headers) {}

    static HttpServer server;
    static int port;
    static final Map<String, Function<Seen, Reply>> routes = new ConcurrentHashMap<>();
    static final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ScmSyncTest::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
        Seen s = new Seen(ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), headers);
        seen.add(s);
        Function<Seen, Reply> route = routes.get(s.path());
        Reply reply = route == null ? new Reply(404, "{\"message\":\"Not Found\"}", Map.of()) : route.apply(s);
        reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        if (reply.status() == 304) {
            ex.sendResponseHeaders(304, -1);
        } else {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired ScmSyncRunner runner;
    MockMvc mvc;
    long plat;
    long plat1;
    long plat2;
    String repo;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        routes.clear();
        seen.clear();
        jdbc.update("delete from issue_web_link");
        TestData.clear(jdbc);
        plat = TestData.project(mvc, "PLAT");
        plat1 = issue(plat, "폴링 커밋");
        plat2 = issue(plat, "폴링 PR");
        repo = "http://127.0.0.1:" + port + "/acme/app";
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── GitHub(Enterprise 경로 /api/v3) ──

    @Test
    void GitHub_커밋_PR_열린_PR의_커밋을_잇고_두번째는_ETag로_변경_없음() throws Exception {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        routes.put("/api/v3/repos/acme/app/commits", s -> "\"c1\"".equals(s.headers().get("if-none-match"))
                ? new Reply(304, null, Map.of("ETag", "\"c1\""))
                : Reply.json("[" + ghCommit('a', "feat: 폴링 [PLAT-1]", now) + "]", "ETag", "\"c1\"", "X-RateLimit-Remaining", "4999"));
        routes.put("/api/v3/repos/acme/app/pulls", s -> "\"p1\"".equals(s.headers().get("if-none-match"))
                ? new Reply(304, null, Map.of())
                : Reply.json("[" + ghPull(7, "PR 연결", "feature/plat-2-poll", "open", null, now) + "]", "ETag", "\"p1\""));
        routes.put("/api/v3/repos/acme/app/pulls/7/commits", s -> Reply.json("[" + ghCommit('b', "wip PLAT-2", now) + "]"));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastOutcome").value("OK"))
                .andExpect(jsonPath("$.apiBaseUrl").value("http://127.0.0.1:" + port + "/api/v3"))
                .andExpect(jsonPath("$.lastMessage").value("커밋 2건(새로 저장 2 · 새 연결 2) · PR 1건(새 연결 1)"));
        assertThat(seen).allSatisfy(s -> assertThat(s.headers().get("authorization")).isEqualTo("Bearer " + TOKEN));
        assertThat(seen.getFirst().query()).contains("sha=main").contains("since=").contains("per_page=100");
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select branch from dev_commit where sha = ?", String.class, "b".repeat(40))).isEqualTo("feature/plat-2-poll");
        assertThat(jdbc.queryForObject("select count(*) from issue_commit where issue_id = ?", Long.class, plat1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from issue_web_link where issue_id = ? and kind = 'PR'", String.class, plat2)).isEqualTo("OPEN");

        seen.clear();
        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastMessage").value("변경 없음"));
        assertThat(seen.stream().map(Seen::path)).containsExactly("/api/v3/repos/acme/app/commits", "/api/v3/repos/acme/app/pulls");
        assertThat(seen.get(0).headers().get("if-none-match")).isEqualTo("\"c1\"");

        // PR이 병합되면 상태가 따라간다 — 개발 정보에도
        routes.put("/api/v3/repos/acme/app/pulls", s -> Reply.json(
                "[" + ghPull(7, "PR 연결", "feature/plat-2-poll", "closed", now, Instant.now().plusSeconds(5).toString()) + "]", "ETag", "\"p2\""));
        run(id).andExpect(status().isOk());
        mvc.perform(get("/api/alm/issues/{id}/development", plat2).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.pullRequests[0].state").value("MERGED"))
                .andExpect(jsonPath("$.pullRequests[0].title").value("#7 PR 연결"));
    }

    @Test
    void 웹훅과_폴링이_같은_커밋을_가져와도_한_번만_남는다() throws Exception {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[" + ghCommit('c', "fix: PLAT-1", now) + "]"));
        routes.put("/api/v3/repos/acme/app/pulls", s -> Reply.json("[]"));
        long id = register(repo, "GITHUB", null);

        JsonNode hook = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + repo + "\",\"provider\":\"GITHUB\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String body = "{\"ref\":\"refs/heads/main\",\"repository\":{\"html_url\":\"" + repo + "\"},\"commits\":[{\"id\":\"" + "c".repeat(40)
                + "\",\"message\":\"fix: PLAT-1\",\"timestamp\":\"" + now + "\",\"author\":{\"name\":\"Dev\",\"email\":\"d@x\"}}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(hook.path("webhook").path("payloadPath").asText()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", "d-1")
                        .header("X-Hub-Signature-256", WebhookSignatures.gitHubHeader(hook.path("secret").asText(), bytes)).content(bytes))
                .andExpect(status().isOk());

        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastMessage").value("커밋 1건(새로 저장 0 · 새 연결 0) · PR 0건(새 연결 0)"));
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from issue_commit", Long.class)).isEqualTo(1);
    }

    @Test
    void 레이트_리밋이면_reset까지_미루고_그동안_지금_동기화는_409() throws Exception {
        long reset = Instant.now().plusSeconds(600).getEpochSecond();
        routes.put("/api/v3/repos/acme/app/commits", s -> new Reply(403, "{\"message\":\"API rate limit exceeded\"}",
                Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", String.valueOf(reset))));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastOutcome").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.rateLimitedUntil").value(Instant.ofEpochSecond(reset).toString()));
        run(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        assertThat(runner.runDue(Instant.now())).isZero();
    }

    @Test
    void 토큰_거부는_FAILED와_백오프_그리고_커서를_움직이지_않는다() throws Exception {
        routes.put("/api/v3/repos/acme/app/commits", s -> new Reply(401, "{\"message\":\"Bad credentials\"}", Map.of()));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.lastMessage").value("토큰이 거부되었습니다(401) — 토큰을 확인하거나 다시 넣으세요"))
                .andExpect(jsonPath("$.failureCount").value(1));
        Instant next = jdbc.queryForObject("select next_run_at from repository_scm_sync where id = ?", Timestamp.class, id).toInstant();
        assertThat(next).isAfter(Instant.now().plusSeconds(4 * 60));
        assertThat(jdbc.queryForObject("select commits_cursor from repository_scm_sync where id = ?", Timestamp.class, id)).isNull();
        run(id).andExpect(jsonPath("$.failureCount").value(2));
        next = jdbc.queryForObject("select next_run_at from repository_scm_sync where id = ?", Timestamp.class, id).toInstant();
        assertThat(next).isAfter(Instant.now().plusSeconds(9 * 60)); // 5분 × 2
    }

    @Test
    void SSRF_가드_API_주소_호스트는_저장소_호스트만이고_리다이렉트와_남의_다음_쪽은_따라가지_않는다() throws Exception {
        registerRepositories(repo);
        mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"" + repo + "\",\"provider\":\"GITHUB\",\"apiBaseUrl\":\"http://169.254.169.254/latest\",\"token\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("API 주소의 호스트는 저장소 호스트(127.0.0.1)여야 합니다"));

        routes.put("/api/v3/repos/acme/app/commits", s -> new Reply(302, null, Map.of("Location", "http://169.254.169.254/")));
        long id = register(repo, "GITHUB", null);
        run(id).andExpect(jsonPath("$.lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.lastMessage").value("Git 서버가 다른 주소로 보냈습니다(302) — 리다이렉트는 따라가지 않습니다. API 주소를 확인하세요"));

        String now = Instant.now().toString();
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[" + ghCommit('d', "x", now) + "]",
                "Link", "<http://localhost:" + port + "/steal?page=2>; rel=\"next\""));
        jdbc.update("update repository_scm_sync set next_run_at = ?, failure_count = 0 where id = ?", Timestamp.from(Instant.now()), id);
        run(id).andExpect(jsonPath("$.lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.lastMessage").value("허용되지 않은 호스트로 요청하지 않습니다(localhost)"));
        assertThat(seen.stream().map(Seen::path)).doesNotContain("/steal");
    }

    // ── GitLab(self-managed /api/v4, 하위 그룹) ──

    @Test
    void GitLab은_PRIVATE_TOKEN과_인코딩된_경로로_커밋과_MR을_읽는다() throws Exception {
        String glRepo = "http://127.0.0.1:" + port + "/group/sub/proj";
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String base = "/api/v4/projects/group%2Fsub%2Fproj";
        routes.put(base + "/repository/commits", s -> Reply.json("[{\"id\":\"" + "e".repeat(40) + "\",\"message\":\"feat: PLAT-1\","
                + "\"author_name\":\"Dev\",\"author_email\":\"d@x\",\"authored_date\":\"" + now + "\",\"committed_date\":\"" + now + "\"}]"));
        routes.put(base + "/merge_requests", s -> Reply.json("[{\"iid\":3,\"title\":\"MR [PLAT-2]\",\"source_branch\":\"plat-2-mr\","
                + "\"state\":\"merged\",\"web_url\":\"" + glRepo + "/-/merge_requests/3\",\"updated_at\":\"" + now + "\"}]"));
        long id = register(glRepo, "GITLAB", null);

        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastOutcome").value("OK"));
        assertThat(seen).allSatisfy(s -> assertThat(s.headers().get("private-token")).isEqualTo(TOKEN));
        assertThat(jdbc.queryForObject("select url from dev_commit", String.class)).isEqualTo(glRepo + "/-/commit/" + "e".repeat(40));
        assertThat(jdbc.queryForObject("select state from issue_web_link where issue_id = ?", String.class, plat2)).isEqualTo("MERGED");
        // 병합된 MR의 커밋은 따로 읽지 않는다(열린 것만)
        assertThat(seen.stream().map(Seen::path)).doesNotContain(base + "/merge_requests/3/commits");
    }

    // ── 관리·임대 ──

    @Test
    void 토큰은_응답에도_DB에도_원문이_없고_관리자만() throws Exception {
        long id = register(repo, "GITHUB", null);
        String list = mvc.perform(get("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncMode").value("both"))
                .andExpect(jsonPath("$.webhooksEnabled").value(true))
                .andExpect(jsonPath("$.pollingEnabled").value(true))
                .andExpect(jsonPath("$.schedulerActive").value(false))
                .andExpect(jsonPath("$.pollIntervalSeconds").value(300))
                .andExpect(jsonPath("$.repositories[0].id").value(id))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(TOKEN);
        assertThat(jdbc.queryForObject("select token_enc from repository_scm_sync", String.class)).doesNotContain(TOKEN);

        // 토큰 없이 고치면 토큰은 그대로(끄기만)
        mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + repo + "\",\"provider\":\"GITHUB\",\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        permissions.denyAction(5, plat, AlmAction.ADMIN);
        mvc.perform(get("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(5, "Bob"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/projects/{p}/settings/scm-sync/{s}/run", plat, id).with(asUser(5, "Bob"))).andExpect(status().isForbidden());

        // 저장소 등록에서 빠지면 함께 지워진다
        registerRepositories("https://github.com/chanho4702/other");
        assertThat(jdbc.queryForObject("select count(*) from repository_scm_sync", Long.class)).isZero();
    }

    @Test
    void 주기_실행은_때가_된_것만_임대를_잡아_한_번_돌고_남이_쥔_임대는_건너뛴다() throws Exception {
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[]"));
        routes.put("/api/v3/repos/acme/app/pulls", s -> Reply.json("[]"));
        long id = register(repo, "GITHUB", null);

        jdbc.update("update repository_scm_sync set lease_owner = 'other', lease_until = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(600)), id);
        assertThat(runner.runDue(Instant.now())).isZero();
        assertThat(seen).isEmpty();

        jdbc.update("update repository_scm_sync set lease_owner = null, lease_until = null where id = ?", id);
        assertThat(runner.runDue(Instant.now())).isEqualTo(1);
        assertThat(runner.runDue(Instant.now())).isZero(); // 다음 실행은 주기 뒤
        assertThat(jdbc.queryForObject("select last_outcome from repository_scm_sync", String.class)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select lease_until from repository_scm_sync", Timestamp.class)).isNull();

        // 꺼 두면 주기 실행 대상이 아니다
        jdbc.update("update repository_scm_sync set enabled = false, next_run_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertThat(runner.runDue(Instant.now())).isZero();
        mvc.perform(delete("/api/alm/projects/{p}/settings/scm-sync/{s}", plat, id).with(asUser(ADMIN, "Alice"))).andExpect(status().isNoContent());
    }

    // ── Codex 지적 회귀 ──

    @Test
    void PR_260건은_쪽_상한을_넘어도_오름차순_키셋으로_끝까지_따라잡고_다_따라잡으면_탐침_ETag로_건너뛴다() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(5, ChronoUnit.DAYS);
        List<String> prs = new java.util.ArrayList<>(); // 오름차순(오래된 것부터) 260건 — 쪽 50 × 상한 5쪽 = 250을 넘는다
        for (int i = 0; i < 260; i++) prs.add(ghPull(1000 + i, "PR " + i, "plat-2-pr" + i, "closed", null, base.plusSeconds(i * 60L).toString()));
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[]"));
        routes.put("/api/v3/repos/acme/app/pulls", ghPullsRoute(prs, "\"probe-260\""));
        long id = register(repo, "GITHUB", null);

        int runs = 0;
        String message;
        do {
            message = JSON.readTree(run(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                    .path("lastMessage").asText();
            runs++;
            assertThat(runs).as("끝내 따라잡아야 한다").isLessThan(20);
        } while (message.contains("이어서 읽는 중"));
        assertThat(runs).isEqualTo(9); // 30건씩
        assertThat(jdbc.queryForObject("select count(*) from issue_web_link where kind = 'PR'", Long.class)).isEqualTo(260);
        assertThat(jdbc.queryForObject("select pulls_cursor from repository_scm_sync", Timestamp.class).toInstant())
                .isEqualTo(base.plusSeconds(259 * 60L));
        assertThat(jdbc.queryForObject("select pulls_cursor_number from repository_scm_sync", Long.class)).isNull();
        assertThat(jdbc.queryForObject("select pulls_page from repository_scm_sync", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select pulls_etag from repository_scm_sync", String.class)).isEqualTo("\"probe-260\"");

        // 다 따라잡은 뒤에는 탐침만 — 304면 목록을 읽지 않는다
        seen.clear();
        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastMessage").value("변경 없음"));
        assertThat(seen.stream().filter(x -> x.path().endsWith("/pulls")).toList()).hasSize(1)
                .allSatisfy(x -> assertThat(x.query()).contains("direction=desc").contains("per_page=1"));

        // 오래된 PR 하나가 갱신되면 맨 뒤로 간다 — 탐침이 바뀌고, 끝 쪽에서 그것만 새로 처리한다
        prs.add(prs.remove(3).replace(base.plusSeconds(3 * 60L).toString(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                .replace("\"closed\"", "\"open\"").replace("\"merged_at\":null", "\"merged_at\":null"));
        routes.put("/api/v3/repos/acme/app/pulls", ghPullsRoute(prs, "\"probe-261\""));
        routes.put("/api/v3/repos/acme/app/pulls/1003/commits", s -> Reply.json("[]"));
        // 경계(커서와 같은 시각 — 마지막 PR)는 다시 처리한다(멱등). 새로 갱신된 것 1건 + 경계 1건
        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastMessage").value("커밋 0건(새로 저장 0 · 새 연결 0) · PR 2건(새 연결 0)"));
        assertThat(jdbc.queryForObject("select state from issue_web_link where url like '%/pull/1003'", String.class)).isEqualTo("OPEN");
    }

    @Test
    void 같은_시각_40건을_번호_내림차순으로_받아도_묶음을_끝까지_처리한다() throws Exception {
        String same = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.DAYS).toString();
        List<String> prs = new java.util.ArrayList<>();
        for (int i = 39; i >= 0; i--) prs.add(ghPull(500 + i, "같은 시각 " + i, "plat-2-same" + i, "closed", null, same)); // 번호 내림차순
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[]"));
        routes.put("/api/v3/repos/acme/app/pulls", ghPullsRoute(prs, "\"probe-same\""));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(jsonPath("$.lastMessage").value("커밋 0건(새로 저장 0 · 새 연결 0) · PR 40건(새 연결 40)"));
        assertThat(jdbc.queryForObject("select count(*) from issue_web_link where kind = 'PR'", Long.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select pulls_cursor from repository_scm_sync", Timestamp.class).toInstant()).isEqualTo(Instant.parse(same));
    }

    @Test
    void 같은_시각이_한_실행_예산보다_많으면_경고와_함께_그_시각을_지나_전진하고_다음_PR은_정상_처리한다() throws Exception {
        Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.DAYS);
        List<String> prs = new java.util.ArrayList<>();
        for (int i = 0; i < 520; i++) prs.add(ghPull(2000 + i, "대량 " + i, "bulk-" + i, "closed", null, same.toString())); // 키 없음
        prs.add(ghPull(9000, "뒤의 PR", "plat-2-after", "closed", null, same.plusSeconds(60).toString()));
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[]"));
        routes.put("/api/v3/repos/acme/app/pulls", ghPullsRoute(prs, "\"probe-bulk\""));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(jsonPath("$.lastMessage").value(org.hamcrest.Matchers.containsString("동일 시각 PR이 너무 많아 일부를 건너뛰었을 수 있음")));
        assertThat(jdbc.queryForObject("select pulls_cursor from repository_scm_sync", Timestamp.class).toInstant()).isAfter(same);
        run(id).andExpect(jsonPath("$.lastMessage").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("동일 시각"))));
        assertThat(jdbc.queryForObject("select count(*) from issue_web_link where url like '%/pull/9000'", Long.class)).isEqualTo(1);
    }

    @Test
    void 물러서기_예산을_다_써도_줄인_쪽_힌트를_남겨_다음_실행이_이어서_물러선다() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(3, ChronoUnit.DAYS);
        List<String> prs = new java.util.ArrayList<>(); // 6쪽(300건) — 전부 커서 뒤
        for (int i = 0; i < 300; i++) prs.add(ghPull(3000 + i, "PR " + i, "nokey-" + i, "closed", null, base.plusSeconds(i * 60L).toString()));
        routes.put("/api/v3/repos/acme/app/commits", s -> Reply.json("[]"));
        routes.put("/api/v3/repos/acme/app/pulls", ghPullsRoute(prs, "\"probe-back\""));
        long id = register(repo, "GITHUB", null);
        // 힌트가 목록 끝을 한참 넘었다(PR이 지워졌거나 당겨졌다)
        jdbc.update("update repository_scm_sync set pulls_cursor = ?, pulls_page = 60 where id = ?", Timestamp.from(base.minusSeconds(60)), id);

        run(id).andExpect(status().isOk());
        Integer hint = jdbc.queryForObject("select pulls_page from repository_scm_sync", Integer.class);
        assertThat(hint).isLessThan(60).isGreaterThan(1); // 예산 안에서 59 → 54 → … 로 물러섰고 그 자리를 남겼다
        assertThat(jdbc.queryForObject("select pulls_cursor from repository_scm_sync", Timestamp.class).toInstant()).isEqualTo(base.minusSeconds(60));

        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastMessage").value(org.hamcrest.Matchers.containsString("PR 30건")));
        assertThat(jdbc.queryForObject("select pulls_cursor from repository_scm_sync", Timestamp.class).toInstant()).isEqualTo(base.plusSeconds(29 * 60L));
    }

    /** 가짜 GitHub PR 목록 — 탐침(desc·per_page=1, If-None-Match 304)과 오름차순 쪽 나누기(Link rel=next) */
    private static Function<Seen, Reply> ghPullsRoute(List<String> ascending, String probeEtag) {
        List<String> snapshot = List.copyOf(ascending);
        return s -> {
            Map<String, String> q = new java.util.HashMap<>();
            for (String kv : s.query() == null ? new String[0] : s.query().split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) q.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            if ("desc".equals(q.get("direction"))) {
                if (probeEtag.equals(s.headers().get("if-none-match"))) return new Reply(304, null, Map.of());
                return Reply.json("[" + (snapshot.isEmpty() ? "" : snapshot.getLast()) + "]", "ETag", probeEtag);
            }
            int per = Integer.parseInt(q.getOrDefault("per_page", "30"));
            int page = Integer.parseInt(q.getOrDefault("page", "1"));
            int from = Math.min(snapshot.size(), (page - 1) * per);
            int to = Math.min(snapshot.size(), from + per);
            String body = "[" + String.join(",", snapshot.subList(from, to)) + "]";
            return to < snapshot.size()
                    ? Reply.json(body, "Link", "<http://127.0.0.1:" + port + s.path() + "?state=all&sort=updated&direction=asc&per_page="
                            + per + "&page=" + (page + 1) + ">; rel=\"next\"")
                    : Reply.json(body);
        };
    }

    @Test
    void 실행_중_임대를_잃으면_결과를_버리고_남의_임대를_덮지_않는다() throws Exception {
        routes.put("/api/v3/repos/acme/app/commits", s -> {
            // 커밋을 읽는 사이 다른 인스턴스가 만료된 임대를 가져갔다
            jdbc.update("update repository_scm_sync set lease_owner = 'other:1', lease_until = ?", Timestamp.from(Instant.now().plusSeconds(600)));
            return Reply.json("[" + ghCommit('f', "feat: PLAT-1", Instant.now().toString()) + "]");
        });
        routes.put("/api/v3/repos/acme/app/pulls", s -> Reply.json("[]"));
        long id = register(repo, "GITHUB", null);

        run(id).andExpect(status().isOk()).andExpect(jsonPath("$.lastOutcome").doesNotExist());
        assertThat(jdbc.queryForObject("select lease_owner from repository_scm_sync", String.class)).isEqualTo("other:1");
        assertThat(jdbc.queryForObject("select commits_cursor from repository_scm_sync", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isZero(); // 임대를 잃은 뒤에는 수집도 하지 않는다
    }

    @Test
    void 수정은_PATCH_의미_API_주소와_꺼짐을_보내지_않으면_그대로() throws Exception {
        String custom = "http://127.0.0.1:" + port + "/ghe/api/v3";
        long id = register(repo, "GITHUB", custom);
        mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + repo + "\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiBaseUrl").value(custom))
                .andExpect(jsonPath("$.enabled").value(false));
        // 토큰만 바꿔도 API 주소·꺼짐이 유지된다
        mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + repo + "\",\"token\":\"ghp_new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.apiBaseUrl").value(custom))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.provider").value("GITHUB"));
    }

    // ── 도우미 ──

    private long register(String url, String provider, String apiBase) throws Exception {
        registerRepositories(url);
        String body = "{\"repositoryUrl\":\"" + url + "\",\"provider\":\"" + provider + "\",\"token\":\"" + TOKEN + "\""
                + (apiBase == null ? "" : ",\"apiBaseUrl\":\"" + apiBase + "\"") + "}";
        String response = mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("id").asLong();
    }

    private ResultActions run(long id) throws Exception {
        return mvc.perform(post("/api/alm/projects/{p}/settings/scm-sync/{s}/run", plat, id).with(asUser(ADMIN, "Alice")));
    }

    private void registerRepositories(String url) throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositories\":[{\"url\":\"" + url + "\"}]}"))
                .andExpect(status().isOk());
    }

    private static String ghCommit(char c, String message, String at) {
        String sha = String.valueOf(c).repeat(40);
        return "{\"sha\":\"" + sha + "\",\"html_url\":\"http://127.0.0.1:" + port + "/acme/app/commit/" + sha + "\",\"commit\":{\"message\":\""
                + message + "\",\"author\":{\"name\":\"Dev\",\"email\":\"d@x\",\"date\":\"" + at + "\"},\"committer\":{\"date\":\"" + at + "\"}}}";
    }

    private static String ghPull(int number, String title, String head, String state, String mergedAt, String updatedAt) {
        return "{\"number\":" + number + ",\"html_url\":\"http://127.0.0.1:" + port + "/acme/app/pull/" + number + "\",\"title\":\"" + title
                + "\",\"state\":\"" + state + "\",\"merged_at\":" + (mergedAt == null ? "null" : "\"" + mergedAt + "\"")
                + ",\"updated_at\":\"" + updatedAt + "\",\"head\":{\"ref\":\"" + head + "\"}}";
    }

    private long issue(long projectId, String title) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"priority\":\"medium\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
