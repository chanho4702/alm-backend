package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.dev.webhook.WebhookSignatures;
import com.platform.almbackend.permission.AlmAction;
import org.junit.jupiter.api.AfterEach;
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

import java.nio.charset.StandardCharsets;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 저장소 웹훅(V44) — 발급·재발급·서명 검증·저장소 대조·전달 멱등·push 수집·PR 연결·org 불능·본문 상한·GitLab */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class RepositoryWebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPO = "https://github.com/chanho4702/alm-backend";
    private static final long ADMIN = 1;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;
    long plat;
    long plat1;
    long plat2;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        jdbc.update("delete from issue_web_link");
        TestData.clear(jdbc);
        plat = TestData.project(mvc, "PLAT");
        plat1 = issue(plat, "웹훅 수신");
        plat2 = issue(plat, "PR 연결");
        registerRepositories(REPO);
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── 관리 ──

    @Test
    void 발급한_시크릿은_한_번만_보이고_목록에는_없다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String secret = issued.path("secret").asText();
        assertThat(secret).matches("[0-9a-f]{64}");
        String path = issued.path("webhook").path("payloadPath").asText();
        assertThat(path).startsWith("/api/alm/webhooks/github/");

        String list = mvc.perform(get("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryptionConfigured").value(true))
                .andExpect(jsonPath("$.webhooks[0].repositoryUrl").value(REPO))
                .andExpect(jsonPath("$.webhooks[0].provider").value("GITHUB"))
                .andExpect(jsonPath("$.webhooks[0].payloadPath").value(path))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(secret);
        // DB에도 원문이 없다(암호문만)
        String stored = jdbc.queryForObject("select secret_enc from repository_webhook", String.class);
        assertThat(stored).doesNotContain(secret);
    }

    @Test
    void 등록하지_않은_저장소와_ADMIN_아닌_사람은_거부한다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"https://github.com/someone/else\",\"provider\":\"GITHUB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("먼저 저장소를 프로젝트에 등록하세요: https://github.com/someone/else"));

        permissions.denyAction(5, plat, AlmAction.ADMIN);
        mvc.perform(post("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(5, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"" + REPO + "\",\"provider\":\"GITHUB\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(5, "Bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재발급하면_경로는_그대로고_옛_시크릿은_거부된다() throws Exception {
        JsonNode first = issue(REPO, "GITHUB");
        JsonNode second = issue(REPO, "GITHUB");
        String path = first.path("webhook").path("payloadPath").asText();
        assertThat(second.path("webhook").path("payloadPath").asText()).isEqualTo(path);
        assertThat(jdbc.queryForObject("select count(*) from repository_webhook", Long.class)).isEqualTo(1);

        github(path, first.path("secret").asText(), "push", "d-old", push("refs/heads/main", commit('a', "feat: PLAT-1")))
                .andExpect(status().isUnauthorized());
        github(path, second.path("secret").asText(), "push", "d-new", push("refs/heads/main", commit('a', "feat: PLAT-1")))
                .andExpect(status().isOk());
    }

    @Test
    void 저장소_등록에서_빠지면_웹훅도_지워진다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        registerRepositories("https://github.com/chanho4702/alm-front");
        assertThat(jdbc.queryForObject("select count(*) from repository_webhook", Long.class)).isZero();
        github(issued.path("webhook").path("payloadPath").asText(), issued.path("secret").asText(), "ping", "d-1", "{\"zen\":\"hi\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void 삭제하면_그_경로는_404다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        long id = issued.path("webhook").path("id").asLong();
        mvc.perform(delete("/api/alm/projects/{p}/settings/webhooks/{w}", plat, id).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());
        github(issued.path("webhook").path("payloadPath").asText(), issued.path("secret").asText(), "ping", "d-1", "{}")
                .andExpect(status().isNotFound());
    }

    // ── 수신(GitHub) ──

    @Test
    void push는_커밋을_수집해_이슈에_잇고_같은_전달의_재전송은_DUPLICATE다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String secret = issued.path("secret").asText();
        String body = push("refs/heads/main", commit('a', "feat(alm): 웹훅 [PLAT-1]") + "," + commit('b', "fix: plat-2 정리"));

        github(path, secret, "push", "delivery-1", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.message").value("커밋 2건 — 새로 저장 2 · 새 연결 2"));
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from issue_commit where issue_id = ?", Long.class, plat1)).isEqualTo(1);
        // 수집 계약 그대로 — 처리 주체는 시크릿을 발급한 관리자, 브랜치는 ref에서
        assertThat(jdbc.queryForObject("select ingested_by from dev_commit where sha = ?", Long.class, "a".repeat(40))).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("select branch from dev_commit where sha = ?", String.class, "a".repeat(40))).isEqualTo("main");

        github(path, secret, "push", "delivery-1", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        // 전달 id가 달라도(새 push가 같은 커밋을 실어도) 수집 멱등이라 늘지 않는다
        github(path, secret, "push", "delivery-2", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.message").value("커밋 2건 — 새로 저장 0 · 새 연결 0 · 이미 있음 2"));
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(2);

        mvc.perform(get("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.webhooks[0].lastOutcome").value("PROCESSED"))
                .andExpect(jsonPath("$.webhooks[0].lastEvent").value("push"))
                .andExpect(jsonPath("$.webhooks[0].recentDeliveries.length()").value(2))
                .andExpect(jsonPath("$.webhooks[0].recentDeliveries[0].deliveryId").value("delivery-2"));
    }

    @Test
    void 서명이_틀리거나_없으면_401이고_아무것도_남지_않는다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String body = push("refs/heads/main", commit('c', "feat: PLAT-1"));

        github(path, "f".repeat(64), "push", "d-1", body).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-2").content(body))
                .andExpect(status().isUnauthorized());
        // 서명은 받은 바이트 그대로 — 본문을 한 글자만 바꿔도 틀린다
        String signature = WebhookSignatures.gitHubHeader(issued.path("secret").asText(), body.getBytes(StandardCharsets.UTF_8));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-3").header("X-Hub-Signature-256", signature).content(body + " "))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from repository_webhook_delivery", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select last_outcome from repository_webhook", String.class)).isEqualTo("REJECTED");
    }

    @Test
    void ping은_PING이고_다른_저장소의_이벤트는_422다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String secret = issued.path("secret").asText();

        github(path, secret, "ping", "d-ping", "{\"zen\":\"Keep it simple.\",\"repository\":{\"html_url\":\"" + REPO + "\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PING"));

        String other = "{\"ref\":\"refs/heads/main\",\"repository\":{\"html_url\":\"https://github.com/evil/repo\"},\"commits\":["
                + commit('d', "feat: PLAT-1") + "]}";
        github(path, secret, "push", "d-other", other).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isZero();
    }

    @Test
    void 태그_push와_처리하지_않는_이벤트는_IGNORED다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String secret = issued.path("secret").asText();
        github(path, secret, "push", "d-tag", push("refs/tags/v1.0.0", commit('e', "feat: PLAT-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        github(path, secret, "issues", "d-issues", "{\"repository\":{\"html_url\":\"" + REPO + "\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isZero();
    }

    @Test
    void pull_request는_제목과_브랜치의_키로_PR_웹_링크를_만든다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String secret = issued.path("secret").asText();
        String body = "{\"action\":\"opened\",\"repository\":{\"html_url\":\"" + REPO + "\"},\"pull_request\":{"
                + "\"html_url\":\"" + REPO + "/pull/7\",\"number\":7,\"title\":\"웹훅 수신 [PLAT-1]\","
                + "\"body\":\"관련 PLAT-99\",\"head\":{\"ref\":\"feature/plat-2-pr-link\"}}}";

        github(path, secret, "pull_request", "d-pr-1", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.message").value("PR — 새 연결 2"));
        github(path, secret, "pull_request", "d-pr-2", body.replace("opened", "synchronize")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PR — 새 연결 0 · 이미 연결 2"));

        assertThat(jdbc.queryForObject("select count(*) from issue_web_link where kind = 'PR' and url = ?", Long.class,
                REPO + "/pull/7")).isEqualTo(2);
        mvc.perform(get("/api/alm/issues/{id}/development", plat2).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequests[0].url").value(REPO + "/pull/7"))
                .andExpect(jsonPath("$.pullRequests[0].title").value("#7 웹훅 수신 [PLAT-1]"));
    }

    @Test
    void 발급한_관리자가_EDIT를_잃으면_키를_건너뛴다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        permissions.denyAction(ADMIN, plat, AlmAction.EDIT);
        github(issued.path("webhook").path("payloadPath").asText(), issued.path("secret").asText(), "push", "d-1",
                push("refs/heads/main", commit('f', "feat: PLAT-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(jdbc.queryForObject("select count(*) from issue_commit", Long.class)).isZero();
    }

    @Test
    void org_불능은_503이고_전달이_남지_않아_재전송하면_다시_처리된다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        String secret = issued.path("secret").asText();
        String body = push("refs/heads/main", commit('1', "feat: PLAT-1"));

        permissions.setUnavailable(true);
        github(path, secret, "push", "d-retry", body).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        permissions.setUnavailable(false);
        assertThat(jdbc.queryForObject("select count(*) from repository_webhook_delivery", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select last_outcome from repository_webhook", String.class)).isEqualTo("FAILED");

        github(path, secret, "push", "d-retry", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(jdbc.queryForObject("select count(*) from issue_commit", Long.class)).isEqualTo(1);
    }

    @Test
    void 본문_상한과_Content_type을_지킨다() throws Exception {
        JsonNode issued = issue(REPO, "GITHUB");
        String path = issued.path("webhook").path("payloadPath").asText();
        byte[] huge = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-big").content(huge))
                .andExpect(status().is(413));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-form").content("payload=%7B%7D"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ── 수신(GitLab) ──

    @Test
    void GitLab은_토큰을_비교하고_Push_Hook을_수집한다() throws Exception {
        JsonNode issued = issue(REPO, "GITLAB");
        String path = issued.path("webhook").path("payloadPath").asText();
        assertThat(path).startsWith("/api/alm/webhooks/gitlab/");
        String body = "{\"object_kind\":\"push\",\"ref\":\"refs/heads/main\",\"after\":\"" + "a".repeat(40) + "\","
                + "\"project\":{\"web_url\":\"" + REPO + "\"},\"commits\":[" + commit('2', "feat: PLAT-2") + "]}";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", "wrong").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", issued.path("secret").asText()).header("Idempotency-Key", "gl-1").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(jdbc.queryForObject("select count(*) from issue_commit where issue_id = ?", Long.class, plat2)).isEqualTo(1);
        // GitHub 경로로는 같은 웹훅에 닿지 않는다
        mvc.perform(post(path.replace("/gitlab/", "/github/")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    // ── 도우미 ──

    private ResultActions github(String path, String secret, String event, String delivery, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", WebhookSignatures.gitHubHeader(secret, bytes))
                .content(bytes));
    }

    private JsonNode issue(String repositoryUrl, String provider) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"" + repositoryUrl + "\",\"provider\":\"" + provider + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void registerRepositories(String url) throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositories\":[{\"url\":\"" + url + "\"}]}"))
                .andExpect(status().isOk());
    }

    private static String push(String ref, String commits) {
        return "{\"ref\":\"" + ref + "\",\"deleted\":false,\"repository\":{\"html_url\":\"" + REPO + "\"},\"commits\":[" + commits + "]}";
    }

    private static String commit(char c, String message) {
        String sha = String.valueOf(c).repeat(40);
        return "{\"id\":\"" + sha + "\",\"message\":\"" + message + "\",\"timestamp\":\"2026-11-07T10:00:00+09:00\","
                + "\"url\":\"" + REPO + "/commit/" + sha + "\",\"author\":{\"name\":\"Dev\",\"email\":\"dev@test.com\"}}";
    }

    private long issue(long projectId, String title) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"priority\":\"medium\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
