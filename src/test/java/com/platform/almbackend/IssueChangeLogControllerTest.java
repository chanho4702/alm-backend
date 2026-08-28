package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 이슈 변경 이력 계약. 번다운과 스프린트 리포트가 "언제 무엇이 완료됐고 스코프가 어떻게 바뀌었나"를
 * 브라우저가 아니라 서버 기록으로 재현해야 하므로, 상태와 스프린트 소속 변경을 서버가 남긴다.
 * 이력은 소급 생성이 불가능하다 — 리포트보다 먼저 쌓기 시작해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueChangeLogControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        projectId = createProject();
    }

    @Test
    void 이슈를_만들면_최초_상태가_이력에_남는다() throws Exception {
        long issueId = createIssue("첫 이슈", "todo", null);

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].issueId").value(issueId))
                .andExpect(jsonPath("$[0].field").value("STATUS"))
                .andExpect(jsonPath("$[0].fromValue").doesNotExist())
                .andExpect(jsonPath("$[0].toValue").value("todo"))
                .andExpect(jsonPath("$[0].actorId").value(1))
                .andExpect(jsonPath("$[0].changedAt").exists());
    }

    @Test
    void 상태를_바꾸면_이전값과_새값이_남고_같은_값_저장은_남지_않는다() throws Exception {
        long issueId = createIssue("작업", "todo", null);
        updateStatus(issueId, "inprogress", 1);
        updateStatus(issueId, "inprogress", 2); // 값이 그대로 — 이력 없음
        mvc.perform(post("/api/alm/issues/{id}/move", issueId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].fromValue").value("todo"))
                .andExpect(jsonPath("$[1].toValue").value("inprogress"))
                .andExpect(jsonPath("$[2].fromValue").value("inprogress"))
                .andExpect(jsonPath("$[2].toValue").value("done"))
                .andExpect(jsonPath("$[2].actorId").value(2));
    }

    @Test
    void 스프린트_편입과_이탈이_이력에_남는다() throws Exception {
        long sprintId = createSprint();
        long issueId = createIssue("작업", "todo", null);

        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":" + sprintId + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("field", "SPRINT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromValue").doesNotExist())
                .andExpect(jsonPath("$[0].toValue").value(String.valueOf(sprintId)))
                .andExpect(jsonPath("$[1].fromValue").value(String.valueOf(sprintId)))
                .andExpect(jsonPath("$[1].toValue").doesNotExist());
    }

    @Test
    void 스프린트_완료로_옮겨진_이슈도_이력에_남는다() throws Exception {
        long sprintId = createSprint();
        long next = createSprint();
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        long issueId = createIssue("남은 것", "inprogress", sprintId);

        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(3, "Carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnfinishedToSprintId\":" + next + "}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("field", "SPRINT"))
                .andExpect(status().isOk())
                // 생성 시 편입 + 완료 시 이관
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].issueId").value(issueId))
                .andExpect(jsonPath("$[1].fromValue").value(String.valueOf(sprintId)))
                .andExpect(jsonPath("$[1].toValue").value(String.valueOf(next)))
                .andExpect(jsonPath("$[1].actorId").value(3));
    }

    @Test
    void 완료_이관_이력의_시각은_스프린트_완료시각과_같다() throws Exception {
        long sprintId = createSprint();
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        createIssue("남은 것", "inprogress", sprintId);

        String sprintBody = mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId)
                        .with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String completedAt = read(sprintBody).get("completedAt").asText();

        // 리포트는 "완료 처리로 한꺼번에 옮긴 것"을 이 동일성으로 식별한다 — 깨지면 미완료 목록이 빈다
        String changes = mvc.perform(get("/api/alm/projects/{id}/changes", projectId)
                        .with(asUser(1, "Alice")).param("field", "SPRINT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode transfer = read(changes).get(1);
        org.junit.jupiter.api.Assertions.assertEquals(completedAt, transfer.get("changedAt").asText());
    }

    @Test
    void 스프린트로_이력을_걸러_볼_수_있다() throws Exception {
        long sprintId = createSprint();
        long inSprint = createIssue("스프린트 안", "todo", sprintId);
        createIssue("백로그", "todo", null);
        updateStatus(inSprint, "done", 1);

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("sprintId", String.valueOf(sprintId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].issueId").value(inSprint))
                .andExpect(jsonPath("$[2].field").value("STATUS"))
                .andExpect(jsonPath("$[2].toValue").value("done"));
    }

    @Test
    void 스프린트_필터는_떠난_쪽과_들어온_쪽_모두에_잡힌다() throws Exception {
        long from = createSprint();
        long to = createSprint();
        long issueId = createIssue("옮겨 다니는 것", "todo", from);

        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":" + to + "}"))
                .andExpect(status().isOk());

        // 원래 스프린트 리포트는 "시작 후 빠진 이슈"를 알아야 한다 — 이탈 이력이 보여야 한다
        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("sprintId", String.valueOf(from))
                        .param("field", "SPRINT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].fromValue").value(String.valueOf(from)))
                .andExpect(jsonPath("$[1].toValue").value(String.valueOf(to)));

        // 받은 쪽에서도 같은 줄이 보인다
        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("sprintId", String.valueOf(to))
                        .param("field", "SPRINT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].issueId").value(issueId));
    }

    @Test
    void 백로그로_빠진_이슈도_원래_스프린트_필터에_잡힌다() throws Exception {
        long from = createSprint();
        long issueId = createIssue("빠지는 것", "todo", from);

        mvc.perform(post("/api/alm/issues/{id}/rank", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(1, "Alice"))
                        .param("sprintId", String.valueOf(from))
                        .param("field", "SPRINT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].fromValue").value(String.valueOf(from)))
                .andExpect(jsonPath("$[1].toValue").doesNotExist());
    }

    @Test
    void 권한이_없으면_이력을_볼_수_없다() throws Exception {
        createIssue("작업", "todo", null);
        permissions.setAllowed(false);

        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).with(asUser(9, "Eve")))
                .andExpect(status().isForbidden());
    }

    private void updateStatus(long issueId, String status, int expectedVersion) throws Exception {
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\""
                                + status + "\",\"priority\":\"MEDIUM\",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private long createProject() throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
    }

    private long createSprint() throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
    }

    private long createIssue(String title, String status, Long sprintId) throws Exception {
        String details = sprintId == null ? "{}" : "{\"sprintId\":" + sprintId + "}";
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"" + status + "\",\"priority\":\"MEDIUM\",\"details\":" + details + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
    }

    private JsonNode read(String body) throws Exception {
        return JSON.readTree(body);
    }
}
