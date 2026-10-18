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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기능 공백 웨이브 2(2026-10-18) — F-10 예정 스프린트 삭제, F-11 이슈 복제, F-12 저장 필터 공유 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class FeatureGapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        jdbc.update("delete from saved_filter");
        jdbc.update("delete from notification");
        jdbc.update("delete from issue_activity");
        jdbc.update("delete from issue_link");
        jdbc.update("delete from issue_comment");
        jdbc.update("delete from issue_label");
        jdbc.update("delete from issue_component");
        jdbc.update("delete from issue");
        jdbc.update("delete from sprint");
        jdbc.update("delete from component");
        jdbc.update("delete from board");
        jdbc.update("delete from project");
        projectId = createProject("FG");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── F-10 ──

    @Test
    void 예정_스프린트를_지우면_이슈는_백로그_맨_위로_돌아간다() throws Exception {
        long backlogOld = createIssue("원래 백로그", "{}");
        long sprint = createSprint("잘못 만든 스프린트");
        long a = createIssue("가", "{\"sprintId\":" + sprint + "}");
        long b = createIssue("나", "{\"sprintId\":" + sprint + "}");
        long c = createIssue("다", "{\"sprintId\":" + sprint + "}");
        permissions.grantAdminOnly(1, projectId);

        // 관리자가 아니면 못 지운다
        mvc.perform(delete("/api/alm/sprints/{id}", sprint).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/alm/sprints/{id}", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/sprints/{id}", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());

        // 백로그 순서: 스프린트에 있던 셋(원래 순서) → 원래 백로그
        List<Long> order = jdbc.queryForList(
                "select id from issue where project_id = ? and sprint_id is null order by sort_order", Long.class, projectId);
        assertThat(order).containsExactly(a, b, c, backlogOld);
        List<Long> sorts = jdbc.queryForList(
                "select sort_order from issue where project_id = ? and sprint_id is null order by sort_order", Long.class, projectId);
        assertThat(sorts).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void 진행_중이거나_끝난_스프린트는_지울_수_없다() throws Exception {
        long sprint = createSprint("진행");
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(delete("/api/alm/sprints/{id}", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("예정 스프린트만 삭제할 수 있습니다"));
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(delete("/api/alm/sprints/{id}", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isConflict());
    }

    // ── F-11 ──

    @Test
    void 복제는_내용만_복사하고_처음_상태로_만들어_원본과_링크한다() throws Exception {
        long component = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/components", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"API\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long sprint = createSprint("S1");
        long parent = createIssue("에픽", "{}");
        long original = id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"로그인 버그\",\"description\":\"<p>재현 절차</p>\",\"type\":\"bug\",\"status\":\"inprogress\","
                                + "\"priority\":\"high\",\"assigneeId\":7,\"details\":{\"parentId\":" + parent + ",\"sprintId\":" + sprint
                                + ",\"labels\":[\"auth\",\"p1\"],\"componentIds\":[" + component + "],\"dueDate\":\"2026-12-01\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/alm/issues/{id}/comments", original).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"원본 댓글\"}")).andExpect(status().isCreated());

        String body = mvc.perform(post("/api/alm/issues/{id}/clone", original).with(asUser(2, "Bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("복제: 로그인 버그"))
                .andExpect(jsonPath("$.type").value("bug"))
                .andExpect(jsonPath("$.description").value("<p>재현 절차</p>"))
                .andExpect(jsonPath("$.priority").value("high"))
                .andExpect(jsonPath("$.labels[0]").value("auth"))
                .andExpect(jsonPath("$.labels[1]").value("p1"))
                .andExpect(jsonPath("$.componentIds[0]").value(component))
                .andExpect(jsonPath("$.parentId").value(parent))
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.sprintId").doesNotExist())
                .andExpect(jsonPath("$.reporterId").value(2))
                .andReturn().getResponse().getContentAsString();
        long copy = id(body);
        assertThat(copy).isNotEqualTo(original);

        mvc.perform(get("/api/alm/issues/{id}/comments", copy).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/issues/{id}/links", copy).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].link.type").value("clones"))
                .andExpect(jsonPath("$[0].link.sourceId").value(copy))
                .andExpect(jsonPath("$[0].other.id").value(original));
    }

    @Test
    void 볼_수_없는_이슈는_복제할_수_없다() throws Exception {
        long original = createIssue("비공개", "{}");
        permissions.deny(2, projectId);
        mvc.perform(post("/api/alm/issues/{id}/clone", original).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from issue", Long.class)).isEqualTo(1L);
    }

    // ── F-12 ──

    @Test
    void 공유_필터는_조직_전체가_보고_수정_삭제는_소유자만() throws Exception {
        long shared = id(mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"우리 팀 버그\",\"kind\":\"aql\",\"query\":\"type = bug\",\"shared\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.ownerId").value(1))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"내 것만\",\"kind\":\"smart\",\"query\":\"로그인\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(false));

        // B의 내 필터 목록에는 없고, 공유 목록에 있다(비공개는 없다)
        mvc.perform(get("/api/alm/me/filters").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/filters/shared").with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("우리 팀 버그"))
                .andExpect(jsonPath("$[0].mine").value(false));
        mvc.perform(get("/api/alm/filters/{id}", shared).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("type = bug"));

        // 수정·삭제는 403
        mvc.perform(put("/api/alm/me/filters/{id}", shared).with(asUser(2, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"가로채기\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/alm/me/filters/{id}", shared).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());

        // 소유자가 공유를 끄면 B에게서 사라진다(단건 조회도 404)
        mvc.perform(put("/api/alm/me/filters/{id}", shared).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shared\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(false))
                .andExpect(jsonPath("$.name").value("우리 팀 버그"));
        mvc.perform(get("/api/alm/filters/shared").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/filters/{id}", shared).with(asUser(2, "Bob")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/alm/me/filters/{id}", shared).with(asUser(2, "Bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 공유_필터의_결과는_실행하는_사람의_권한으로_걸러진다() throws Exception {
        long other = createProject("FH");
        createIssue("보이는 버그", "{}", "bug", projectId);
        createIssue("숨은 버그", "{}", "bug", other);
        mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"버그 전부\",\"kind\":\"aql\",\"query\":\"type = bug\",\"shared\":true}"))
                .andExpect(status().isCreated());
        String query = JSON.readTree(mvc.perform(get("/api/alm/filters/shared").with(asUser(2, "Bob")))
                .andReturn().getResponse().getContentAsString()).get(0).get("query").asText();
        permissions.setAccessScope(com.platform.almbackend.permission.AccessScope.of(java.util.Set.of(projectId)));
        mvc.perform(post("/api/alm/issues/query").with(asUser(2, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("aql", query))))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("보이는 버그"));
    }

    // ── 헬퍼 ──

    private long createProject(String key) throws Exception {
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createSprint(String name) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createIssue(String title, String details) throws Exception {
        return createIssue(title, details, "task", projectId);
    }

    private long createIssue(String title, String details, String type, long project) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + type + "\",\"status\":\"todo\","
                                + "\"priority\":\"medium\",\"details\":" + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static long id(String body) throws Exception {
        JsonNode node = JSON.readTree(body);
        return node.get("id").asLong();
    }

    @SuppressWarnings("unused")
    private static List<Long> ids(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }
}
