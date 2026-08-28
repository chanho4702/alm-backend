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
 * 스프린트 계획 계약 — 계획 메타(목표·시작/종료 예정일)와 완료 시 미완료 이슈 이관 대상.
 * 스프린트가 "무엇을 위해 언제까지"인지를 서버가 보존해야 번다운의 시간축과 스프린트 리포트가
 * 성립하고, 완료 처리가 다음 스프린트로 이어져야 계획이 끊기지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SprintPlanningControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private long sprintId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        projectId = createProject();
        sprintId = createSprint();
    }

    @Test
    void 스프린트에_목표와_기간을_저장한다() throws Exception {
        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"결제 실패율 절반으로",
                                 "plannedStart":"2026-09-01","plannedEnd":"2026-09-12","expectedVersion":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value("결제 실패율 절반으로"))
                .andExpect(jsonPath("$.plannedStart").value("2026-09-01"))
                .andExpect(jsonPath("$.plannedEnd").value("2026-09-12"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].goal").value("결제 실패율 절반으로"))
                .andExpect(jsonPath("$[0].plannedEnd").value("2026-09-12"));
    }

    @Test
    void 시작_예정일이_종료_예정일보다_늦으면_거부한다() throws Exception {
        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","plannedStart":"2026-09-12",
                                 "plannedEnd":"2026-09-01","expectedVersion":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("시작 예정일은 종료 예정일보다 늦을 수 없습니다"));

        mvc.perform(get("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].plannedStart").doesNotExist());
    }

    @Test
    void 목표와_기간은_비울_수_있다() throws Exception {
        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"임시 목표","plannedStart":"2026-09-01",
                                 "plannedEnd":"2026-09-12","expectedVersion":1}"""))
                .andExpect(status().isOk());

        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"  ","expectedVersion":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").doesNotExist())
                .andExpect(jsonPath("$.plannedStart").doesNotExist())
                .andExpect(jsonPath("$.plannedEnd").doesNotExist());
    }

    @Test
    void 다른_사용자가_먼저_수정했으면_충돌한다() throws Exception {
        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"첫 저장","expectedVersion":1}"""))
                .andExpect(status().isOk());

        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"덮어쓰기","expectedVersion":1}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void 진행_중인_스프린트도_목표를_고칠_수_있다() throws Exception {
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sprint 1","goal":"진행 중 재정의","expectedVersion":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.goal").value("진행 중 재정의"));
    }

    @Test
    void 스프린트_단건을_조회한다() throws Exception {
        mvc.perform(get("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sprintId))
                .andExpect(jsonPath("$.name").value("Sprint 1"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/alm/sprints/{id}", sprintId + 9999).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 완료할_때_미완료_이슈를_지정한_스프린트로_옮긴다() throws Exception {
        long next = createSprint();
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        long finished = createIssue("끝난 것", "done", sprintId);
        long unfinished = createIssue("남은 것", "inprogress", sprintId);

        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnfinishedToSprintId\":" + next + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"));

        mvc.perform(get("/api/alm/issues/{id}", unfinished).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").value(next))
                .andExpect(jsonPath("$.order").value(1));
        mvc.perform(get("/api/alm/issues/{id}", finished).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").value(sprintId));
    }

    @Test
    void 이관_대상은_같은_프로젝트의_끝나지_않은_다른_스프린트여야_한다() throws Exception {
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        createIssue("남은 것", "inprogress", sprintId);

        // 자기 자신으로는 옮길 수 없다
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnfinishedToSprintId\":" + sprintId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("완료하는 스프린트로는 이관할 수 없습니다"));

        // 다른 프로젝트의 스프린트도 안 된다
        long otherProject = createProject("oth", "다른 제품");
        long otherSprint = createSprintIn(otherProject);
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnfinishedToSprintId\":" + otherSprint + "}"))
                .andExpect(status().isBadRequest());

        // 스프린트는 여전히 진행 중이다 — 거부된 완료가 상태를 바꾸지 않았다
        mvc.perform(get("/api/alm/sprints/{id}", sprintId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void 이관_대상이_이미_완료된_스프린트면_거부한다() throws Exception {
        long done = createSprint();
        mvc.perform(post("/api/alm/sprints/{id}/start", done).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alm/sprints/{id}/complete", done).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alm/sprints/{id}/start", sprintId).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        createIssue("남은 것", "inprogress", sprintId);

        mvc.perform(post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnfinishedToSprintId\":" + done + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("완료된 스프린트로는 이관할 수 없습니다"));
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

    private long createProject(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
    }

    private long createSprintIn(long targetProjectId) throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/sprints", targetProjectId).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
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

    private JsonNode read(String body) throws Exception {
        return JSON.readTree(body);
    }
}
