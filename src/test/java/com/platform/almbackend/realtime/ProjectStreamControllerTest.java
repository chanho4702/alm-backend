package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/alm/projects/{id}/stream} — 권한(401·403·404·503을 스트림 전에), 도메인 이벤트 → 신호(커밋된 것만, 본문 없음),
 * Last-Event-ID 재연결, 사용자당 상한 429(JSON). 팬아웃은 테스트 설정(local)이다 — Redis 팬아웃은 {@link RedisRealtimeBusTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ProjectStreamControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_TITLE = "외부에 새면 안 되는 제목";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired ProjectStreamHub hub;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        for (String table : new String[]{"notification", "issue_watcher", "issue_activity", "issue_change_log",
                "issue_label", "issue_component", "issue", "sprint", "project_settings", "project_planning", "project"}) {
            jdbc.update("delete from " + table);
        }
        projectId = JSON.readTree(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"RT\",\"name\":\"실시간\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
        permissions.reset();
    }

    private MvcResult open(long userId, String lastEventId) throws Exception {
        var builder = get("/api/alm/projects/{id}/stream", projectId).accept(MediaType.TEXT_EVENT_STREAM)
                .with(asUser(userId, "U" + userId));
        if (lastEventId != null) builder = builder.header("Last-Event-ID", lastEventId);
        return mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
    }

    private long createIssue(String title) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"본문도 비밀\",\"type\":\"TASK\","
                                + "\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static String awaitContent(MvcResult result, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String body = result.getResponse().getContentAsString();
            if (body.contains(needle)) return body;
            if (System.nanoTime() > deadline) throw new AssertionError("'" + needle + "'를 기다리다 시간 초과:\n" + body);
            Thread.sleep(20);
        }
    }

    private static List<String> events(String body, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?:id:([^\\n]+)\\n)?event:" + name + "\\ndata:([^\\n]*)\\n").matcher(body);
        while (m.find()) out.add((m.group(1) == null ? "" : m.group(1)) + "|" + m.group(2));
        return out;
    }

    @Test
    void 인증_없으면_401() throws Exception {
        mvc.perform(get("/api/alm/projects/{id}/stream", projectId)).andExpect(status().isUnauthorized());
    }

    @Test
    void 볼_수_없으면_403_JSON이고_구독이_생기지_않는다() throws Exception {
        permissions.deny(5, projectId);
        mvc.perform(get("/api/alm/projects/{id}/stream", projectId).accept(MediaType.TEXT_EVENT_STREAM).with(asUser(5, "Mallory")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());
        assertThat(hub.subscriberCount()).isZero();
    }

    @Test
    void 권한_서비스_불능은_503_없는_프로젝트는_404() throws Exception {
        permissions.setUnavailable(true);
        mvc.perform(get("/api/alm/projects/{id}/stream", projectId).accept(MediaType.TEXT_EVENT_STREAM).with(asUser(2, "Bob")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        permissions.setUnavailable(false);
        mvc.perform(get("/api/alm/projects/{id}/stream", 999_999).accept(MediaType.TEXT_EVENT_STREAM).with(asUser(2, "Bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이슈_스프린트_변경이_커밋된_것만_본문_없이_id_version_종류로_흐른다() throws Exception {
        MvcResult stream = open(2, null);
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        awaitContent(stream, "event:ready");

        // 실패한 요청(400 — 제목 없음)은 신호를 내지 않는다
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\",\"description\":\"\"}"))
                .andExpect(status().isBadRequest());

        long issue = createIssue(SECRET_TITLE);
        long other = createIssue("둘째");
        awaitContent(stream, "\"issueId\":" + other);

        mvc.perform(put("/api/alm/issues/{id}/flag", issue).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/alm/issues/{id}/move", issue).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"inprogress\"}")).andExpect(status().isOk());
        // 같은 그룹 안 순서만 바꾼다 → ranked
        mvc.perform(post("/api/alm/issues/{id}/rank", other).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"beforeId\":" + issue + "}")).andExpect(status().isOk());
        long sprint = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(delete("/api/alm/issues/{id}", other).with(asUser(1, "Alice"))).andExpect(status().isNoContent());

        String body = awaitContent(stream, "issue.deleted");
        body = awaitContent(stream, "sprint.started");

        List<String> issues = events(body, "issue");
        assertThat(issues).hasSize(6);
        assertThat(issues.get(0)).contains("\"type\":\"issue.created\"").contains("\"issueId\":" + issue)
                .contains("\"issueKey\":\"RT-1\"").contains("\"version\":1").contains("\"actorId\":1");
        assertThat(issues.get(2)).contains("\"type\":\"issue.updated\"").contains("\"changedFields\":[\"flagged\"]");
        assertThat(issues.get(3)).contains("\"type\":\"issue.transitioned\"").contains("\"status\"");
        assertThat(issues.get(4)).contains("\"type\":\"issue.ranked\"").contains("\"issueId\":" + other);
        assertThat(issues.get(5)).contains("\"type\":\"issue.deleted\"").doesNotContain("\"version\"");
        List<String> sprints = events(body, "sprint");
        assertThat(sprints).hasSize(2);
        assertThat(sprints.get(0)).contains("\"type\":\"sprint.created\"").contains("\"sprintId\":" + sprint);
        assertThat(sprints.get(1)).contains("\"type\":\"sprint.started\"").contains("\"version\":2");
        // 모든 신호에 단조 증가 id가 붙는다
        assertThat(issues).allSatisfy(e -> assertThat(e).matches("\\d+-\\d+\\|.*"));
        // 본문 금지 — 제목·설명은 한 글자도 흐르지 않는다
        assertThat(body).doesNotContain(SECRET_TITLE).doesNotContain("본문도 비밀").doesNotContain("title");
    }

    @Test
    void Last_Event_ID로_다시_붙으면_끊긴_사이의_신호만_재생된다() throws Exception {
        MvcResult first = open(2, null);
        awaitContent(first, "event:ready");
        createIssue("끊기 전");
        String body = awaitContent(first, "issue.created");
        String lastId = events(body, "issue").get(0).split("\\|")[0];
        hub.shutdown(); // 연결이 끊겼다

        long missed = createIssue("끊긴 사이");

        MvcResult again = open(2, lastId);
        String replayed = awaitContent(again, "event:ready");
        List<String> issues = events(replayed, "issue");
        assertThat(issues).singleElement().asString().contains("\"issueId\":" + missed);
        assertThat(replayed).contains("\"replayed\":1").doesNotContain("event:reset");

        // 쿼리 파라미터로도 된다(fetch 클라이언트), 이어 줄 수 없는 위치면 reset
        MvcResult gap = mvc.perform(get("/api/alm/projects/{id}/stream", projectId).param("lastEventId", "99999999999999-0")
                        .accept(MediaType.TEXT_EVENT_STREAM).with(asUser(2, "Bob")))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(awaitContent(gap, "event:ready")).contains("event:reset\ndata:{\"projectId\":" + projectId + ",\"reason\":\"gap\"}");
    }

    @Test
    void 사용자당_상한을_넘으면_429_JSON() throws Exception {
        for (int i = 0; i < 5; i++) open(3, null);
        mvc.perform(get("/api/alm/projects/{id}/stream", projectId).accept(MediaType.TEXT_EVENT_STREAM).with(asUser(3, "Carol")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("사용자당 5개")));
    }
}
