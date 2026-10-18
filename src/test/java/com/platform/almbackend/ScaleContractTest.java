package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.history.IssueChangeLogService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 규모·계약 보강(2026-10-18 감사 A-07·A-08·A-09·A-10·A-11·A-13·A-14·A-20) — 새로 생긴 필드·엔드포인트와
 * DB로 내린 필터가 예전 의미를 그대로 지키는지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ScaleContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        // 서블릿 필터는 webAppContextSetup이 자동으로 붙이지 않는다 — 운영처럼 본문 상한 필터를 건다
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilters(context.getBean(com.platform.almbackend.issue.ImportBodyLimitFilter.class)).build();
        permissions.reset();
        jdbc.update("delete from notification");
        jdbc.update("delete from issue_change_log");
        jdbc.update("delete from issue_link");
        jdbc.update("delete from issue_label");
        jdbc.update("delete from issue_component");
        jdbc.update("delete from issue");
        jdbc.update("delete from sprint");
        jdbc.update("delete from project_version");
        jdbc.update("delete from board");
        jdbc.update("delete from project");
        projectId = createProject("SC");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── A-13 LIKE 이스케이프 ──

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() throws Exception {
        createIssue("진행률 100% 달성", "{}");
        createIssue("평범한 제목", "{}");
        createIssue("snake_case 이름", "{}");
        createIssue("snakeXcase 이름", "{}");
        mvc.perform(get("/api/alm/issues/search").param("text", "%").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("진행률 100% 달성"));
        mvc.perform(get("/api/alm/issues/search").param("text", "snake_case").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1));
    }

    // ── A-09 truncated ──

    @Test
    void 검색과_AQL은_뒤에_결과가_더_있으면_truncated를_알린다() throws Exception {
        for (int i = 0; i < 3; i++) createIssue("이슈 " + i, "{}");
        mvc.perform(get("/api/alm/issues/search").param("size", "2").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.truncated").value(true));
        mvc.perform(get("/api/alm/issues/search").param("size", "2").param("page", "1").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"\",\"size\":2}"))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void 버전_진행률은_200건을_넘어도_집계로_정확히_센다() throws Exception {
        long version = id(mvc.perform(post("/api/alm/projects/{id}/versions", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"1.0\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long first = createIssue("기준", "{}");
        // 250건을 SQL로 복제 — 진행률 분모가 200에서 잘리지 않는지 본다
        for (int i = 0; i < 250; i++) {
            jdbc.update("""
                    insert into issue (project_id, issue_number, issue_key, title, description, issue_type, status, priority,
                                       reporter_id, sort_order, version, created_at, updated_at, fix_version_id)
                    select project_id, ? , ?, title, description, issue_type, ?, priority, reporter_id, ?, 1,
                           created_at, updated_at, ? from issue where id = ?
                    """, 1000 + i, "SC-" + (1000 + i), i < 100 ? "done" : "todo", 1000 + i, version, first);
        }
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "statusCategory")
                        .param("fixVersionId", String.valueOf(version)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(250));
        mvc.perform(get("/api/alm/issues/search").param("fixVersionId", String.valueOf(version))
                        .param("size", "200").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(250))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    // ── A-07 보드 DB 필터 ──

    @Test
    void 보드는_스프린트_담당자_타입_라벨을_DB에서_거르되_예전_의미를_지킨다() throws Exception {
        long sprint = id(mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"S1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long inSprintMine = createIssue("스프린트·내 것", "{\"sprintId\":" + sprint + ",\"labels\":[\"ui\"]}", 7L, "bug");
        long inSprintUnassigned = createIssue("스프린트·미지정", "{\"sprintId\":" + sprint + "}", null, "task");
        createIssue("백로그", "{\"labels\":[\"ui\"]}", 7L, "bug");
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());

        long scrum = boards().get(0);
        mvc.perform(get("/api/alm/boards/{id}/issues", scrum).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(inSprintMine))
                .andExpect(jsonPath("$[1].id").value(inSprintUnassigned));

        updateFilter(scrum, "{\"assigneeIds\":[\"unassigned\"],\"types\":[],\"labels\":[]}");
        mvc.perform(get("/api/alm/boards/{id}/issues", scrum).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inSprintUnassigned));

        updateFilter(scrum, "{\"assigneeIds\":[\"7\",\"unassigned\"],\"types\":[\"bug\"],\"labels\":[\"ui\"]}");
        mvc.perform(get("/api/alm/boards/{id}/issues", scrum).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inSprintMine))
                .andExpect(jsonPath("$[0].labels[0]").value("ui"));

        // 칸반은 스프린트 무관 — 라벨 필터만 걸면 백로그 것까지 잡힌다
        long kanban = id(mvc.perform(post("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"칸반\",\"type\":\"kanban\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        updateFilter(kanban, "{\"assigneeIds\":[],\"types\":[],\"labels\":[\"ui\"]}");
        mvc.perform(get("/api/alm/boards/{id}/issues", kanban).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── A-08 변경 이력 기간·상한 ──

    @Test
    void 변경_이력은_기간과_상한을_받고_잘리면_최신_쪽을_남기며_헤더로_알린다() throws Exception {
        for (int i = 0; i < 3; i++) createIssue("이력 " + i, "{}");
        // 이슈 생성마다 STATUS 한 줄
        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("X-Result-Truncated", "false"))
                .andExpect(header().string("X-Result-Limit", String.valueOf(IssueChangeLogService.MAX_ROWS)));
        String limited = mvc.perform(get("/api/alm/projects/{id}/changes", projectId).param("limit", "2").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(header().string("X-Result-Truncated", "true"))
                .andReturn().getResponse().getContentAsString();
        // 시간 오름차순 유지, 남은 것은 최신 두 줄
        JsonNode rows = JSON.readTree(limited);
        assertThat(rows.get(0).get("id").asLong()).isLessThan(rows.get(1).get("id").asLong());
        long newest = jdbc.queryForObject("select max(id) from issue_change_log", Long.class);
        assertThat(rows.get(1).get("id").asLong()).isEqualTo(newest);

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).param("until", "2000-01-01T00:00:00Z").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/projects/{id}/changes", projectId)
                        .param("since", "2030-01-01T00:00:00Z").param("until", "2020-01-01T00:00:00Z").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest());
    }

    // ── A-10 상태 메타 일괄 ──

    @Test
    void 접근_가능한_프로젝트의_상태를_한_번에_준다() throws Exception {
        long other = createProject("SD");
        long trashed = createProject("SE");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/alm/projects/{id}", trashed)
                .with(asUser(1, "Alice"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/projects/statuses").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$['" + projectId + "'][0].id").value("todo"))
                .andExpect(jsonPath("$['" + projectId + "'][0].kind").value("new"))
                .andExpect(jsonPath("$['" + other + "'][2].kind").value("complete"))
                .andExpect(jsonPath("$['" + trashed + "']").doesNotExist());
        // 범위가 좁은 사용자에게는 그 프로젝트만
        permissions.setAccessScope(com.platform.almbackend.permission.AccessScope.of(java.util.Set.of(other)));
        mvc.perform(get("/api/alm/projects/statuses").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$['" + other + "']").exists());
    }

    // ── A-11 일괄 편집 ──

    @Test
    void 일괄_편집은_보낸_필드만_바꾸고_행별로_충돌을_보고한다() throws Exception {
        long a = createIssue("가", "{\"labels\":[\"old\",\"keep\"]}", 5L, "task");
        long b = createIssue("나", "{}", 5L, "task");
        long c = createIssue("다", "{}", 5L, "task");
        int va = version(a);
        int vb = version(b);
        // b는 그 사이 다른 사람이 고쳤다
        mvc.perform(put("/api/alm/issues/{id}", b).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"나 수정\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\",\"assigneeId\":5,\"expectedVersion\":" + vb + "}"))
                .andExpect(status().isOk());

        String body = "{\"items\":[{\"issueId\":" + a + ",\"expectedVersion\":" + va + "},"
                + "{\"issueId\":" + b + ",\"expectedVersion\":" + vb + "},"
                + "{\"issueId\":" + c + "},{\"issueId\":999999}],"
                + "\"changes\":{\"status\":\"inprogress\",\"assigneeId\":null,\"addLabels\":[\"new\"],\"removeLabels\":[\"old\"]}}";
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].code").value("OK"))
                .andExpect(jsonPath("$.results[0].key").value("SC-1"))
                .andExpect(jsonPath("$.results[0].issue.status").value("inprogress"))
                .andExpect(jsonPath("$.results[0].issue.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.results[0].issue.labels[0]").value("keep"))
                .andExpect(jsonPath("$.results[0].issue.labels[1]").value("new"))
                .andExpect(jsonPath("$.results[0].issue.title").value("가"))
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].code").value("CONFLICT"))
                .andExpect(jsonPath("$.results[1].key").value("SC-2"))
                .andExpect(jsonPath("$.results[1].currentVersion").value(vb + 1))
                .andExpect(jsonPath("$.results[2].code").value("OK"))
                .andExpect(jsonPath("$.results[3].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.results[3].key").doesNotExist());
        // 충돌 행은 그대로
        mvc.perform(get("/api/alm/issues/{id}", b).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.title").value("나 수정"));
        // 보내지 않은 필드(우선순위·라벨 없는 c)는 그대로
        mvc.perform(get("/api/alm/issues/{id}", c).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.priority").value("medium"))
                .andExpect(jsonPath("$.labels[0]").value("new"));
    }

    @Test
    void 일괄_편집은_권한없는_행과_잘못된_요청을_가른다() throws Exception {
        long other = createProject("SF");
        long mine = createIssue("내 프로젝트", "{}");
        long hidden = createIssueIn(other, "숨은 이슈");
        permissions.deny(2, other);
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(2, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + mine + "},{\"issueId\":" + hidden + "}],\"changes\":{\"priority\":\"high\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("OK"))
                .andExpect(jsonPath("$.results[1].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.results[1].key").doesNotExist());
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + mine + "}],\"changes\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("바꿀 내용이 없습니다"));
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[],\"changes\":{\"priority\":\"high\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + mine + "}],\"changes\":{\"status\":\"없는상태\"}}"))
                .andExpect(jsonPath("$.results[0].code").value("INVALID"));
    }

    // ── A-14 가져오기 상한 ──

    @Test
    void 가져오기는_항목_수와_본문_크기에_상한이_있다() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            if (i > 0) items.append(',');
            items.append("{\"title\":\"t").append(i).append("\"}");
        }
        mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from issue", Long.class)).isZero();

        String huge = "x".repeat(21 * 1024 * 1024);
        mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"title\":\"" + huge + "\"}]}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── A-20 순환 검사 ──

    @Test
    void 깊은_계층에서도_순환을_한_번에_잡는다() throws Exception {
        long root = createIssue("뿌리", "{}");
        long parent = root;
        long leaf = root;
        for (int i = 0; i < 5; i++) {
            leaf = createIssue("단계 " + i, "{\"parentId\":" + parent + "}");
            parent = leaf;
        }
        mvc.perform(put("/api/alm/issues/{id}", root).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"뿌리\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\","
                                + "\"details\":{\"parentId\":" + leaf + ",\"labels\":[]},\"expectedVersion\":" + version(root) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상위 항목이 순환합니다"));
        // 순환이 아닌 재배치는 된다
        long sibling = createIssue("형제", "{}");
        mvc.perform(put("/api/alm/issues/{id}", sibling).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"형제\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\","
                                + "\"details\":{\"parentId\":" + leaf + ",\"labels\":[]},\"expectedVersion\":" + version(sibling) + "}"))
                .andExpect(status().isOk());
    }

    // ── 헬퍼 ──

    private long createProject(String key) throws Exception {
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createIssue(String title, String details) throws Exception {
        return createIssue(title, details, null, "task");
    }

    private long createIssue(String title, String details, Long assigneeId, String type) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + type + "\",\"status\":\"todo\","
                                + "\"priority\":\"medium\",\"assigneeId\":" + assigneeId + ",\"details\":" + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createIssueIn(long project, String title) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private int version(long issueId) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString()).get("version").asInt();
    }

    private List<Long> boards() throws Exception {
        JsonNode list = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private void updateFilter(long boardId, String filter) throws Exception {
        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"filter\":" + filter + "}"))
                .andExpect(status().isOk());
    }

    private static long id(String body) throws Exception {
        return JSON.readTree(body).get("id").asLong();
    }
}
