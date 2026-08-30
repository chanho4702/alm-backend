package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWatcherRepository;
import com.platform.almbackend.repository.NotificationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워처·알림 계약. 알림 대상은 워처 ∪ 담당자 − 행위자이고, 보고자·담당자는 자동 워처다.
 * 서버는 문장을 만들지 않는다(type + detail만) — 문장은 프론트가 이름표로 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class NotificationControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired IssueWatcherRepository watchers;
    @Autowired NotificationRepository notifications;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private long issueId;
    private long version;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        watchers.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        issueId = JSON.readTree(issue).get("id").asLong();
        version = JSON.readTree(issue).get("version").asLong();
    }

    private String update(String status, Long assigneeId) {
        return "{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"" + status
                + "\",\"priority\":\"MEDIUM\",\"assigneeId\":" + assigneeId
                + ",\"expectedVersion\":" + version + ",\"details\":{}}";
    }

    @Test
    void 보고자는_자동_워처이고_상태가_바뀌면_행위자를_뺀_워처가_알림을_받는다() throws Exception {
        mvc.perform(get("/api/alm/issues/{id}/watchers", issueId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true))
                .andExpect(jsonPath("$.watchers.length()").value(1));

        // Bob이 관심 등록 — 멱등
        mvc.perform(put("/api/alm/issues/{id}/watchers/me", issueId).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true))
                .andExpect(jsonPath("$.watchers.length()").value(2));
        mvc.perform(put("/api/alm/issues/{id}/watchers/me", issueId).with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.watchers.length()").value(2));

        // Alice가 상태를 바꾼다 → Bob만 받는다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("inprogress", null)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[0].detail").value("inprogress"))
                .andExpect(jsonPath("$[0].actorId").value(1))
                .andExpect(jsonPath("$[0].read").value(false));
        mvc.perform(get("/api/alm/notifications").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 담당자가_되면_알림을_받고_워처가_되며_읽음은_본인만() throws Exception {
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("todo", 3L)))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/alm/notifications").with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].issueKey").value("ALM-1"))
                .andReturn().getResponse().getContentAsString();
        long notificationId = JSON.readTree(body).get(0).get("id").asLong();
        mvc.perform(get("/api/alm/issues/{id}/watchers", issueId).with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.watching").value(true))
                .andExpect(jsonPath("$.watchers.length()").value(2));

        mvc.perform(post("/api/alm/notifications/{id}/read", notificationId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/notifications/{id}/read", notificationId).with(asUser(3, "Carol")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/notifications").with(asUser(3, "Carol")))
                .andExpect(jsonPath("$[0].read").value(true));
    }

    @Test
    void 관심을_끄면_더_받지_않고_모두_읽음이_된다() throws Exception {
        mvc.perform(put("/api/alm/issues/{id}/watchers/me", issueId).with(asUser(2, "Bob")));
        mvc.perform(delete("/api/alm/issues/{id}/watchers/me", issueId).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(false));
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("done", null)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));

        // Alice는 보고자지만 자기 행동이라 알림이 없다; read-all은 204
        mvc.perform(post("/api/alm/notifications/read-all").with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }
}
