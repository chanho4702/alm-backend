package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웨이브 D2·D3(V30) — 플래그·시작일·스토리 포인트. 계획 필드는 "보낸 키만 바뀐다", 플래그는 version을 올리지 않는다,
 * AQL·합계·속도가 새 필드를 센다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PlanningFieldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "PL");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 시작일과_포인트는_보낸_키만_바뀌고_옛_클라이언트의_저장은_지우지_않는다() throws Exception {
        JsonNode created = create("계획", "{\"dueDate\":\"2026-11-10\"}", "{\"startDate\":\"2026-11-01\",\"storyPoints\":3}");
        long id = created.get("id").asLong();
        assertThat(created.get("startDate").asText()).isEqualTo("2026-11-01");
        assertThat(created.get("storyPoints").decimalValue()).isEqualByComparingTo("3");
        assertThat(created.get("flagged").asBoolean()).isFalse();
        assertThat(created.get("triageState").isNull()).isTrue();

        // planning 없이 저장(옛 클라이언트) — 시작일·포인트는 그대로
        JsonNode kept = update(id, created.get("version").asInt(), "{\"dueDate\":\"2026-11-12\"}", null);
        assertThat(kept.get("startDate").asText()).isEqualTo("2026-11-01");
        assertThat(kept.get("storyPoints").decimalValue()).isEqualByComparingTo("3");

        // 포인트만 비운다 — 시작일은 그대로
        JsonNode cleared = update(id, kept.get("version").asInt(), "{\"dueDate\":\"2026-11-12\"}", "{\"storyPoints\":null}");
        assertThat(cleared.get("storyPoints").isNull()).isTrue();
        assertThat(cleared.get("startDate").asText()).isEqualTo("2026-11-01");

        // 활동에 남는다
        mvc.perform(get("/api/alm/issues/{id}/activity", id).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[?(@.type == 'storypoints')]").isNotEmpty());
    }

    @Test
    void 시작일이_마감일보다_늦으면_400이다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("역전", "{\"dueDate\":\"2026-11-01\"}", "{\"startDate\":\"2026-11-02\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("시작일은 마감일보다 늦을 수 없습니다"));

        // 마감일만 앞당겨도 걸린다(최종 값끼리 비교)
        JsonNode ok = create("정상", "{\"dueDate\":\"2026-11-10\"}", "{\"startDate\":\"2026-11-05\"}");
        mvc.perform(put("/api/alm/issues/{id}", ok.get("id").asLong()).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("정상", ok.get("version").asInt(), "{\"dueDate\":\"2026-11-01\"}", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("시작일은 마감일보다 늦을 수 없습니다"));
    }

    @Test
    void 포인트는_음수와_소수_둘째_자리를_거절한다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody("음수", "{}", "{\"storyPoints\":-1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody("정밀", "{}", "{\"storyPoints\":1.25}")))
                .andExpect(status().isBadRequest());
        JsonNode half = create("반 점", "{}", "{\"storyPoints\":0.5}");
        assertThat(half.get("storyPoints").decimalValue()).isEqualByComparingTo("0.5");
    }

    @Test
    void 필드_구성에서_필수로_건_시작일은_생성_때_필요하다() throws Exception {
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.body.fields[?(@.id == 'startDate')]").isNotEmpty())
                .andExpect(jsonPath("$.body.fields[?(@.id == 'storyPoints')]").isNotEmpty());
    }

    @Test
    void 플래그는_version을_올리지_않고_이유를_코멘트로_남긴다() throws Exception {
        JsonNode issue = create("막힘", "{}", null);
        long id = issue.get("id").asLong();
        int version = issue.get("version").asInt();

        mvc.perform(put("/api/alm/issues/{id}/flag", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":true,\"comment\":\"API 키 대기\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").value(true))
                .andExpect(jsonPath("$.version").value(version));

        // 열어 둔 편집 폼(같은 version)의 저장은 409가 아니고, 플래그를 되돌리지 않는다
        JsonNode saved = update(id, version, "{}", null);
        assertThat(saved.get("flagged").asBoolean()).isTrue();

        mvc.perform(get("/api/alm/issues/{id}/comments", id).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].body").value("플래그 추가: API 키 대기"));

        // 편집 권한이 없으면 403
        permissions.denyAction(2, projectId, com.platform.almbackend.permission.AlmAction.EDIT);
        mvc.perform(put("/api/alm/issues/{id}/flag", id).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일괄_편집으로_플래그를_켠다() throws Exception {
        long a = create("가", "{}", null).get("id").asLong();
        long b = create("나", "{}", null).get("id").asLong();
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + a + "},{\"issueId\":" + b + "}],\"changes\":{\"flagged\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.results[0].issue.flagged").value(true));
    }

    @Test
    void AQL로_플래그_시작일_포인트를_찾고_정렬한다() throws Exception {
        long flagged = create("막힌 것", "{}", "{\"storyPoints\":5,\"startDate\":\"2026-11-03\"}").get("id").asLong();
        create("작은 것", "{}", "{\"storyPoints\":1}");
        create("추정 없음", "{}", null);
        mvc.perform(put("/api/alm/issues/{id}/flag", flagged).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":true}")).andExpect(status().isOk());

        assertThat(keys("flagged = true")).containsExactly("막힌 것");
        assertThat(keys("막힘 = false ORDER BY summary")).containsExactly("작은 것", "추정 없음");
        assertThat(keys("storyPoints >= 2")).containsExactly("막힌 것");
        assertThat(keys("포인트 IS EMPTY")).containsExactly("추정 없음");
        assertThat(keys("startDate >= 2026-11-01")).containsExactly("막힌 것");
        assertThat(keys("storyPoints IS NOT EMPTY ORDER BY storyPoints DESC")).containsExactly("막힌 것", "작은 것");

        mvc.perform(get("/api/alm/issues/query/fields").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fields[?(@.name == 'startDate')].kind").value("DATE"))
                .andExpect(jsonPath("$.fields[?(@.name == 'storyPoints')].kind").value("NUMBER"))
                .andExpect(jsonPath("$.fields[?(@.name == 'flagged')].kind").value("BOOL"));
    }

    @Test
    void 백로그_스프린트_합계를_포인트와_시간으로_센다() throws Exception {
        long sprint = TestData.sprint(mvc, projectId, "S1");
        create("가", "{\"sprintId\":" + sprint + ",\"estimateHours\":2}", "{\"storyPoints\":3}");
        long b = create("나", "{\"sprintId\":" + sprint + "}", "{\"storyPoints\":5}").get("id").asLong();
        create("다", "{\"sprintId\":" + sprint + "}", null);
        create("백로그", "{}", "{\"storyPoints\":8}");
        move(b, "done");

        JsonNode summary = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/estimation/summary", projectId)
                        .with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode s1 = summary.get("groups").get(0);
        assertThat(s1.get("sprintId").asLong()).isEqualTo(sprint);
        assertThat(s1.get("issueCount").asLong()).isEqualTo(3);
        assertThat(s1.get("completedCount").asLong()).isEqualTo(1);
        assertThat(s1.get("storyPoints").decimalValue()).isEqualByComparingTo("8");
        assertThat(s1.get("completedStoryPoints").decimalValue()).isEqualByComparingTo("5");
        assertThat(s1.get("unestimatedCount").asLong()).isEqualTo(1);
        assertThat(s1.get("estimateHours").decimalValue()).isEqualByComparingTo("2");
        JsonNode backlog = summary.get("groups").get(1);
        assertThat(backlog.get("sprintId").isNull()).isTrue();
        assertThat(backlog.get("name").asText()).isEqualTo("백로그");
        assertThat(backlog.get("storyPoints").decimalValue()).isEqualByComparingTo("8");
    }

    @Test
    void 속도는_완료한_양과_완료_시점_범위를_고른_단위로_센다() throws Exception {
        long sprint = TestData.sprint(mvc, projectId, "S1");
        long done = create("끝", "{\"sprintId\":" + sprint + "}", "{\"storyPoints\":5}").get("id").asLong();
        create("이월", "{\"sprintId\":" + sprint + "}", "{\"storyPoints\":3}");
        move(done, "done");
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/reports/velocity", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("points"))
                .andExpect(jsonPath("$.sprints[0].sprintId").value(sprint))
                .andExpect(jsonPath("$.sprints[0].completed").value(5))
                .andExpect(jsonPath("$.sprints[0].committed").value(8))
                .andExpect(jsonPath("$.average").value(5));
        mvc.perform(get("/api/alm/projects/{id}/reports/velocity", projectId).param("unit", "count").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprints[0].completed").value(1))
                .andExpect(jsonPath("$.sprints[0].committed").value(2));
        mvc.perform(get("/api/alm/projects/{id}/reports/velocity", projectId).param("unit", "days").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("추정 단위는 points·hours·count 중 하나입니다"));
    }

    // ── 도우미 ──

    private void move(long id, String status) throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/move", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private List<String> keys(String aql) throws Exception {
        JsonNode body = JSON.readTree(mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(java.util.Map.of("aql", aql))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> titles = new ArrayList<>();
        for (JsonNode item : body.get("items")) titles.add(item.get("title").asText());
        return titles;
    }

    private JsonNode create(String title, String details, String planning) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody(title, details, planning)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode update(long id, int version, String details, String planning) throws Exception {
        String title = JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", id).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString()).get("title").asText();
        return JSON.readTree(mvc.perform(put("/api/alm/issues/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(title, version, details, planning)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String issueBody(String title, String details, String planning) {
        return "{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\","
                + "\"details\":" + details + (planning == null ? "" : ",\"planning\":" + planning) + "}";
    }

    private static String updateBody(String title, int version, String details, String planning) {
        return "{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\","
                + "\"expectedVersion\":" + version + ",\"details\":" + details
                + (planning == null ? "" : ",\"planning\":" + planning) + "}";
    }
}
