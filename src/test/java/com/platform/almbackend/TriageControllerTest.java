package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 트리아지(V35) — 들어오는 길(편집 권한 없는 열람자·명시적으로 보냄), 대기열 밖 노출(보드·목록에서 빠짐),
 * 처리(수락·중복·거절·보류)와 권한.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class TriageControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long VIEWER = 2;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired com.platform.almbackend.automation.AutomationScheduler scheduler;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "TR");
        permissions.denyAction(VIEWER, projectId, AlmAction.EDIT);
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 트리아지가_꺼져_있으면_열람자는_예전처럼_만들_수_없다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(VIEWER, "Viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue("요청", "")))
                .andExpect(status().isForbidden());
        // 편집자가 triage=true를 보내도 꺼진 프로젝트면 400
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue("요청", ",\"triage\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("트리아지를 켜지 않은 프로젝트입니다"));
    }

    @Test
    void 트리아지_설정은_관리자만_바꾼다() throws Exception {
        permissions.grantAdminOnly(1, projectId);
        mvc.perform(put("/api/alm/projects/{id}/triage/settings", projectId).with(asUser(3, "Editor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        enable();
        mvc.perform(get("/api/alm/projects/{id}/triage/settings", projectId).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.pending").value(0));
    }

    @Test
    void 열람자가_올린_이슈는_대기열로_가고_보드와_목록에서_빠진다() throws Exception {
        enable();
        long sprint = TestData.sprint(mvc, projectId, "S1");
        JsonNode submitted = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(VIEWER, "Viewer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"로그인 안 됨\",\"description\":\"\",\"details\":{\"sprintId\":" + sprint + "}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(submitted.get("triageState").asText()).isEqualTo("PENDING");
        assertThat(submitted.get("sprintId").isNull()).isTrue(); // 대기열은 계획 밖 — 스프린트에 넣지 않는다
        long id = submitted.get("id").asLong();
        long normal = create("일반");

        mvc.perform(get("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(normal));
        long board = boardId();
        mvc.perform(get("/api/alm/boards/{id}/issues", board).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(get("/api/alm/projects/{id}/triage", projectId).with(asUser(VIEWER, "Viewer")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        // 검색(AQL)에는 나온다 — triage 필드로 거른다
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"triage = true\"}"))
                .andExpect(jsonPath("$.total").value(1));

        // 열람자는 처리할 수 없다
        mvc.perform(post("/api/alm/issues/{id}/triage/accept", id).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isForbidden());

        // 수락 — 백로그로, 지정한 담당자·우선순위
        mvc.perform(post("/api/alm/issues/{id}/triage/accept", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":7,\"priority\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triageState").doesNotExist())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.assigneeId").value(7))
                .andExpect(jsonPath("$.priority").value("high"))
                .andExpect(jsonPath("$.sprintId").doesNotExist());
        mvc.perform(get("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
        // 두 번 처리하면 400
        mvc.perform(post("/api/alm/issues/{id}/triage/decline", id).with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("트리아지 대기열에 없는 이슈입니다"));
    }

    @Test
    void 거절은_하지_않음으로_중복은_링크와_함께_끝낸다() throws Exception {
        enable();
        long original = create("원본");
        long declined = submit("필요 없음");
        long dup = submit("같은 요청");

        mvc.perform(post("/api/alm/issues/{id}/triage/decline", declined).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"범위 밖\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.resolution").value("WONT_DO"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        mvc.perform(get("/api/alm/issues/{id}/comments", declined).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].body").value("범위 밖"));

        mvc.perform(post("/api/alm/issues/{id}/triage/duplicate", dup).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"duplicateOfIssueId\":" + original + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("DUPLICATE"));
        mvc.perform(get("/api/alm/issues/{id}/links", dup).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].link.type").value("duplicates"))
                .andExpect(jsonPath("$[0].other.id").value(original));
    }

    @Test
    void 보류한_이슈는_시각이_지나면_다시_대기로_보인다() throws Exception {
        enable();
        long id = submit("다음 분기");
        mvc.perform(post("/api/alm/issues/{id}/triage/snooze", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        String until = Instant.now().plus(2, ChronoUnit.DAYS).toString();
        mvc.perform(post("/api/alm/issues/{id}/triage/snooze", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triageState").value("SNOOZED"));
        mvc.perform(get("/api/alm/projects/{id}/triage", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/projects/{id}/triage", projectId).param("state", "snoozed").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));

        // 보류 시각을 과거로 돌리면(시간이 흐른 것) 대기로 보인다
        jdbc.update("update issue set triage_snoozed_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), id);
        mvc.perform(get("/api/alm/projects/{id}/triage", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].triageState").value("PENDING"));
        mvc.perform(get("/api/alm/projects/{id}/triage/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.snoozed").value(0));
    }

    @Test
    void 편집자는_기존_이슈를_대기열로_보내고_만들_때_triage로_보낼_수_있다() throws Exception {
        enable();
        long sprint = TestData.sprint(mvc, projectId, "S1");
        long id = TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"재검토\",\"description\":\"\",\"details\":{\"sprintId\":" + sprint + "}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/alm/issues/{id}/triage", id).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triageState").value("PENDING"))
                .andExpect(jsonPath("$.sprintId").doesNotExist());
        mvc.perform(post("/api/alm/issues/{id}/triage", id).with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue("바로 트리아지", ",\"triage\":true")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triageState").value("PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from issue where triage_source = 'MANUAL'", Long.class)).isEqualTo(2);
    }

    /**
     * Codex P2: 트리아지 대기 이슈는 수락·중복·거절로만 대기열을 나간다 — 일반 전환·보드 이동·PUT·일괄 상태 변경·자동화
     * TRANSITION이 상태·해결을 바꾸면 트리아지 처리를 우회한다. 상태를 건드리지 않는 편집은 그대로 된다.
     */
    @Test
    void 트리아지_대기_이슈는_일반_상태_변경_경로로_옮길_수_없다() throws Exception {
        enable();
        long id = submit("대기 중");
        String blocked = "트리아지 대기열에 있는 이슈는 트리아지에서 먼저 처리해야 합니다";

        mvc.perform(post("/api/alm/issues/{id}/transitions", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transitionId\":\"implicit:done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(blocked));
        // 목록도 숨기지 않되 이유와 함께 비활성
        mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions[0].available").value(false))
                .andExpect(jsonPath("$.transitions[0].unavailableReason").value(blocked));
        mvc.perform(post("/api/alm/issues/{id}/move", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(blocked));
        mvc.perform(put("/api/alm/issues/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(id, "done", "대기 중")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(blocked));
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + id + "}],\"changes\":{\"status\":\"done\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.results[0].code").value("INVALID"))
                .andExpect(jsonPath("$.results[0].error").value(blocked));

        // 자동화 TRANSITION도 같은 관문 — 예약 규칙이 대기 이슈를 옮기지 못하고 실패로 남는다
        long ruleId = TestData.id(mvc.perform(post("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"대기 정리","trigger":{"type":"SCHEDULED","cron":"0 9 * * *"},"condition":"triage = true",
                                 "actions":[{"type":"TRANSITION","toStatus":"done"}]}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        jdbc.update("update automation_rule set next_run_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(5)), ruleId);
        assertThat(scheduler.runDue(Instant.now())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select message from automation_run where rule_id = ? order by id desc limit 1",
                String.class, ruleId)).contains(blocked);

        JsonNode now = JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", id).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        assertThat(now.get("status").asText()).isEqualTo("todo");
        assertThat(now.get("triageState").asText()).isEqualTo("PENDING");
        assertThat(now.has("resolution") && !now.get("resolution").isNull()).isFalse();

        // 상태를 건드리지 않는 편집은 막지 않는다
        mvc.perform(put("/api/alm/issues/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(id, "todo", "제목 고침")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목 고침"));
    }

    @Test
    void 권한_서비스_불능은_대기열로_새지_않고_503이다() throws Exception {
        enable();
        permissions.setUnavailable(true);
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(VIEWER, "Viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue("요청", "")))
                .andExpect(status().isServiceUnavailable());
    }

    // ── 도우미 ──

    private void enable() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/triage/settings", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private long submit(String title) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(VIEWER, "Viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue(title, "")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long create(String title) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issue(title, "")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long boardId() throws Exception {
        JsonNode boards = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode board : boards) if (!"scrum".equals(board.get("type").asText())) return board.get("id").asLong();
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"칸반\",\"type\":\"kanban\"}"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void 검색과_집계의_triage_조건은_AQL과_같이_대기열을_가르고_total도_맞는다() throws Exception {
        enable();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content(issue("일반 1", ""))).andExpect(status().isCreated());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content(issue("일반 2", ""))).andExpect(status().isCreated());
        long waiting = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content(issue("대기", ",\"triage\":true")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long snoozed = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content(issue("보류", ",\"triage\":true")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/alm/issues/{id}/triage/snooze", snoozed).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"" + java.time.Instant.now().plus(java.time.Duration.ofDays(1)) + "\"}"))
                .andExpect(status().isOk());

        // 기본(조건 없음)은 옛 동작 — 전부
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(4));
        // 보드·백로그용 — 대기열 밖만, total도 서버가 맞춘다
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId)).param("triage", "false")
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].triageState").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
        // 대기열(보류 포함) — AQL triage = true와 같은 집합
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId)).param("triage", "true")
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.containsInAnyOrder((int) waiting, (int) snoozed)));
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"project = TR AND triage = true\"}"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "status").param("projectIds", String.valueOf(projectId))
                        .param("triage", "false").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/alm/issues/search").param("triage", "maybe").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest());
    }

    private String updateBody(long id, String status, String title) throws Exception {
        JsonNode current = JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", id).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        return "{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + current.get("type").asText()
                + "\",\"status\":\"" + status + "\",\"priority\":\"" + current.get("priority").asText()
                + "\",\"details\":{},\"expectedVersion\":" + current.get("version").asInt() + "}";
    }

    private static String issue(String title, String extra) {
        return "{\"title\":\"" + title + "\",\"description\":\"\"" + extra + "}";
    }
}
