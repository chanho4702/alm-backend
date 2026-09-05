package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.AuditLogRepository;
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

import static com.platform.almbackend.TestAuth.asAdmin;
import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리 콘솔 계약 — 감사 로그는 도메인 이벤트와 함께 남고, ADMIN 역할만 읽는다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AdminControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired AuditLogRepository auditLogs;
    @Autowired TestConfig.FakePermissionClient permissions;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        auditLogs.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        // 전역 관리자는 org-service의 GLOBAL/ADMIN grant다 — JWT 역할이 아니다(2026-09-05)
        permissions.setGlobalAdmins(9);
    }

    @Test
    void 프로젝트와_이슈_변경이_감사_로그로_남고_관리자만_본다() throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/alm/admin/audit").with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/alm/admin/audit").with(asAdmin(9, "Root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$.items[0].actorId").value(2))
                .andExpect(jsonPath("$.items[0].targetKey").value("ALM-1"))
                .andExpect(jsonPath("$.items[0].summary").value("작업"))
                .andExpect(jsonPath("$.items[1].eventType").value("PROJECT_CREATED"))
                .andExpect(jsonPath("$.items[1].projectId").value(projectId));

        mvc.perform(get("/api/alm/admin/audit").param("type", "PROJECT_CREATED").with(asAdmin(9, "Root")))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/alm/admin/stats").with(asAdmin(9, "Root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").value(1))
                .andExpect(jsonPath("$.issues").value(1))
                .andExpect(jsonPath("$.auditEntries").value(2));
    }
}
