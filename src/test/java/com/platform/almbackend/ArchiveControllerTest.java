package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 보관·휴지통 — 보관된 이슈는 목록·검색에서 빠지고 복원되며, 삭제된 프로젝트는 휴지통에서 복원·영구 삭제된다 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ArchiveControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private int version;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from issue");
        jdbc.update("delete from project");
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        version = JSON.readTree(body).get("version").asInt();
    }

    private long createIssue(String title) throws Exception {
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(issue).get("id").asLong();
    }

    @Test
    void 보관한_이슈는_목록_검색에서_빠지고_보관함에서_복원된다() throws Exception {
        long a = createIssue("보관할 이슈");
        createIssue("남는 이슈");
        mvc.perform(post("/api/alm/issues/{id}/archive", a).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/alm/issues/{id}", a).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/projects/{id}/issues/archived", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("보관할 이슈"));
        mvc.perform(post("/api/alm/issues/{id}/restore", a).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(post("/api/alm/issues/{id}/restore", a).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 보관된_프로젝트는_읽기_전용이고_해제하면_다시_편집된다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/archive", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"막힘\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("보관된 프로젝트는 읽기만 할 수 있습니다"));
        mvc.perform(put("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"이름 바꿈\",\"description\":\"\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alm/projects").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].archivedAt").exists());
        mvc.perform(post("/api/alm/projects/{id}/unarchive", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        createIssue("다시 됨");
    }

    @Test
    void 삭제는_휴지통_이동이고_복원하거나_영구_삭제한다() throws Exception {
        long a = createIssue("휴지통 이슈");
        mvc.perform(delete("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/projects").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/projects/trash").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deletedAt").exists());
        mvc.perform(post("/api/alm/projects/{id}/restore", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
        mvc.perform(get("/api/alm/issues/{id}", a).with(asUser(1, "Alice")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/alm/projects/{id}/permanent", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/projects/trash").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/alm/projects/{id}/permanent", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
    }
}
