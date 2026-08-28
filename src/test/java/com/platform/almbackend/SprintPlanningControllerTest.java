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
 * 스프린트 계획 메타(목표·시작/종료 예정일) 계약. 스프린트가 "무엇을 위해 언제까지"인지를
 * 서버가 보존해야 번다운의 시간축과 스프린트 리포트가 성립한다.
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
