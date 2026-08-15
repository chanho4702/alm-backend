package com.platform.almbackend;

import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.almbackend.TestAuth.asUser;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ProjectIssueControllerTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.RecordingEventPublisher events;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        events.clear();
    }

    @Test
    void 프로젝트와_이슈를_만들고_이벤트를_발행한다() throws Exception {
        String project = mvc.perform(post("/api/alm/projects")
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"alm","name":"ALM 제품","description":"통합 이슈 관리"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ALM"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long projectId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(project).get("id").asLong();

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"로그인 오류","description":"OIDC callback 실패","type":"BUG","status":"todo","priority":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ALM-1"))
                .andExpect(jsonPath("$.reporterId").value(1))
                .andExpect(jsonPath("$.version").value(1));

        org.assertj.core.api.Assertions.assertThat(permissions.grantedProjectId()).isEqualTo(projectId);
        org.assertj.core.api.Assertions.assertThat(events.events())
                .extracting(EventEnvelope -> EventEnvelope.getPayloadCase().name())
                .containsExactly("PROJECT_CREATED", "ISSUE_CREATED");
    }

    @Test
    void 이슈_수정은_expectedVersion을_검사한다() throws Exception {
        long projectId = createProject();
        long issueId = createIssue(projectId);

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수정","description":"본문","type":"TASK","status":"inprogress","priority":"MEDIUM","assigneeId":2,"expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"덮어쓰기","description":"본문","type":"TASK","status":"done","priority":"LOW","expectedVersion":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(endsWith("현재 2, 요청 1")));
    }

    @Test
    void 권한이_없으면_이슈_조회와_쓰기를_거부한다() throws Exception {
        long projectId = createProject();
        permissions.setAllowed(false);

        mvc.perform(get("/api/alm/projects/{id}/issues", projectId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"금지\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/alm/projects"))
                .andExpect(status().isUnauthorized());
    }

    private long createProject() throws Exception {
        String body = mvc.perform(post("/api/alm/projects")
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"TST\",\"name\":\"테스트\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    private long createIssue(long projectId) throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"첫 이슈\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }
}
