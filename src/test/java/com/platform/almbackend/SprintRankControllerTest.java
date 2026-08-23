package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스프린트 수명주기와 순위 변경 계약. 프론트 목업(jiraStore)의 listSprints/createSprint/
 * startSprint/completeSprint·moveIssue·rankIssue와 같은 규칙을 서버에서 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SprintRankControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.RecordingEventPublisher events;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        events.clear();
        projectId = createProject("alm", "ALM 제품");
    }

    @Test
    void 스프린트는_프로젝트_안에서_순번으로_자동_명명된다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sprint 1"))
                .andExpect(jsonPath("$.state").value("PLANNED"))
                .andExpect(jsonPath("$.startedAt").doesNotExist());

        mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"릴리스 준비\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("릴리스 준비"));

        mvc.perform(get("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Sprint 1"))
                .andExpect(jsonPath("$[1].name").value("릴리스 준비"));
    }

    @Test
    void 진행_중인_스프린트는_프로젝트당_하나뿐이다() throws Exception {
        long first = createSprint();
        long second = createSprint();

        mvc.perform(post("/api/alm/sprints/{id}/start", first).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.startedAt").exists());

        mvc.perform(post("/api/alm/sprints/{id}/start", second).with(asUser(1, "Alice")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/alm/sprints/{id}/start", first).with(asUser(1, "Alice")))
                .andExpect(status().isConflict());
    }

    @Test
    void 스프린트를_완료하면_미완료_이슈만_백로그로_돌아간다() throws Exception {
        long sprintId = createSprint();
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        long backlog = createIssue("백로그에 남아있던 것", "todo", null);
        long finished = createIssue("끝난 것", "done", sprintId);
        long unfinished = createIssue("남은 것", "inprogress", sprintId);

        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.completedAt").exists());

        mvc.perform(get("/api/alm/issues/{id}", finished).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").value(sprintId));
        mvc.perform(get("/api/alm/issues/{id}", unfinished).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").doesNotExist())
                // 백로그 맨 뒤 — 이미 있던 이슈 다음 자리다.
                .andExpect(jsonPath("$.order").value(2));
        mvc.perform(get("/api/alm/issues/{id}", backlog).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.order").value(1));

        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isConflict());
    }

    @Test
    void 보드_이동은_대상_컬럼_안에서_beforeId_앞에_놓고_순서를_다시_매긴다() throws Exception {
        long first = createIssue("첫째", "todo", null);
        long second = createIssue("둘째", "todo", null);
        long moved = createIssue("옮길 것", "inprogress", null);

        mvc.perform(post("/api/alm/issues/{id}/move", moved).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"todo\",\"beforeId\":" + second + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.order").value(2))
                // 순서 변경은 편집 폼의 낙관적 락과 무관하다 — version은 그대로다.
                .andExpect(jsonPath("$.version").value(1));

        assertOrder(first, 1);
        assertOrder(second, 3);
    }

    @Test
    void beforeId가_대상_컬럼에_없으면_조용히_컬럼_맨_뒤에_놓는다() throws Exception {
        long other = createIssue("다른 컬럼", "done", null);
        long first = createIssue("첫째", "todo", null);
        long moved = createIssue("옮길 것", "inprogress", null);

        // beforeId가 done 컬럼 이슈라 todo 컬럼에서는 못 찾는다 → todo 마지막(첫째) 다음 자리.
        // order는 랭크 그룹(백로그) 전역 순서라 done 이슈까지 이어 센다.
        mvc.perform(post("/api/alm/issues/{id}/move", moved).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"todo\",\"beforeId\":" + other + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order").value(3));

        assertOrder(other, 1);
        assertOrder(first, 2);
    }

    @Test
    void 보드_이동은_랭크_그룹의_순서를_유일하게_유지한다() throws Exception {
        // Codex P1 회귀: 컬럼별로 1부터 다시 매기면 같은 랭크 그룹 안에서 번호가 충돌했다.
        long todoA = createIssue("todo A", "todo", null);
        long doneX = createIssue("done X", "done", null);
        long todoB = createIssue("todo B", "todo", null);

        mvc.perform(post("/api/alm/issues/{id}/move", todoB).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"todo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order").value(2));

        // todo A(1) → todo B(2) → done X(3): 세 값이 모두 다르다.
        assertOrder(todoA, 1);
        assertOrder(doneX, 3);
    }

    @Test
    void 음수_beforeId는_요청_검증에서_거부된다() throws Exception {
        long issueId = createIssue("이슈", "todo", null);
        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beforeId\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 랭크_이동은_스프린트_그룹을_바꾸고_양쪽_순서를_다시_매긴다() throws Exception {
        long sprintId = createSprint();
        long backlogFirst = createIssue("백로그1", "todo", null);
        long backlogSecond = createIssue("백로그2", "todo", null);
        long sprintFirst = createIssue("스프린트1", "todo", sprintId);

        mvc.perform(post("/api/alm/issues/{id}/rank", backlogSecond).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":" + sprintId + ",\"beforeId\":" + sprintFirst + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprintId").value(sprintId))
                .andExpect(jsonPath("$.order").value(1));

        assertOrder(sprintFirst, 2);
        assertOrder(backlogFirst, 1);

        // 본문 없이 부르면 백로그 맨 뒤로 돌아간다.
        mvc.perform(post("/api/alm/issues/{id}/rank", backlogSecond).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprintId").doesNotExist())
                .andExpect(jsonPath("$.order").value(2));
        assertOrder(sprintFirst, 1);
    }

    @Test
    void 다른_프로젝트의_스프린트로는_옮길_수_없다() throws Exception {
        long otherProject = createProject("oth", "다른 제품");
        String other = mvc.perform(post("/api/alm/projects/{id}/sprints", otherProject).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long otherSprint = JSON.readTree(other).get("id").asLong();
        long issueId = createIssue("이슈", "todo", null);

        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":" + otherSprint + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 수정으로_스프린트가_바뀌면_대상_컬럼_맨_뒤로_간다() throws Exception {
        long sprintId = createSprint();
        long existing = createIssue("스프린트에 있던 것", "todo", sprintId);
        long moved = createIssue("옮길 것", "todo", null);

        mvc.perform(put("/api/alm/issues/{id}", moved).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"옮길 것","description":"","type":"TASK","status":"todo","priority":"MEDIUM",
                                 "details":{"sprintId":%d},"expectedVersion":1}
                                """.formatted(sprintId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprintId").value(sprintId))
                .andExpect(jsonPath("$.order").value(2))
                .andExpect(jsonPath("$.version").value(2));

        assertOrder(existing, 1);
    }

    @Test
    void EDIT_권한이_없으면_스프린트도_순위도_바꿀_수_없다() throws Exception {
        long sprintId = createSprint();
        long issueId = createIssue("이슈", "todo", null);
        permissions.setAllowed(false);

        mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/issues/{id}/move", issueId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isForbidden());
    }

    private void assertOrder(long issueId, int expected) throws Exception {
        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order").value(expected));
    }

    private long createProject(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private long createSprint() throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private long createIssue(String title, String status, Long sprintId) throws Exception {
        String details = sprintId == null ? "{}" : "{\"sprintId\":" + sprintId + "}";
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"" + status + "\",\"priority\":\"MEDIUM\",\"details\":" + details + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }
}
