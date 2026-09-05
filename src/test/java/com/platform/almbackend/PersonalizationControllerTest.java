package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectShortcutRepository;
import com.platform.almbackend.repository.SystemSettingRepository;
import com.platform.almbackend.repository.UserPreferenceRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지라 메뉴에서 가져온 것들 — 프로젝트 세부(기본 담당자), 바로 가기, 개인 설정(알림·자동 관찰), 공지 배너 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PersonalizationControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired NotificationRepository notifications;
    @Autowired ProjectShortcutRepository shortcuts;
    @Autowired UserPreferenceRepository preferences;
    @Autowired SystemSettingRepository systemSettings;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private int version;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        shortcuts.deleteAllInBatch();
        preferences.deleteAllInBatch();
        systemSettings.deleteAllInBatch();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        // 전역 관리자는 org-service의 GLOBAL/ADMIN grant다 — JWT 역할이 아니다(2026-09-05)
        permissions.setGlobalAdmins(1);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leadId").value(1))
                .andExpect(jsonPath("$.defaultAssignee").value("unassigned"))
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        version = JSON.readTree(body).get("version").asInt();
    }

    @Test
    void 프로젝트_세부를_고치면_기본_담당자_규칙이_이슈_생성에_적용된다() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ALM 제품\",\"description\":\"\",\"category\":\"플랫폼\",\"leadId\":2,\"defaultAssignee\":\"lead\",\"icon\":\"rocket\",\"color\":\"purple\",\"url\":\"https://example.com\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("플랫폼"))
                .andExpect(jsonPath("$.leadId").value(2))
                .andExpect(jsonPath("$.defaultAssignee").value("lead"))
                .andExpect(jsonPath("$.icon").value("rocket"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"담당자 없이\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeId").value(2));
        mvc.perform(put("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ALM 제품\",\"description\":\"\",\"defaultAssignee\":\"someone\",\"expectedVersion\":" + (version + 1) + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 바로_가기는_관리자만_만들고_http_URL만_받는다() throws Exception {
        String created = mvc.perform(post("/api/alm/projects/{id}/shortcuts", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"위키\",\"url\":\"https://wiki.example.com/alm\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order").value(1))
                .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(created).get("id").asLong();
        mvc.perform(post("/api/alm/projects/{id}/shortcuts", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"나쁜 링크\",\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/alm/shortcuts/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"팀 위키\",\"url\":\"https://wiki.example.com/alm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("팀 위키"));
        mvc.perform(get("/api/alm/projects/{id}/shortcuts", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/alm/shortcuts/{id}", id).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 개인_설정은_기본값으로_읽히고_알림_끄기와_자동_관찰이_서버에서_강제된다() throws Exception {
        mvc.perform(get("/api/alm/me/preferences").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.notifications.assigned").value(true))
                .andExpect(jsonPath("$.autoWatch.commented").value(true))
                .andExpect(jsonPath("$.autoWatch.edited").value(false))
                .andExpect(jsonPath("$.startPage").value("home"))
                // 이메일 채널은 기본 꺼짐, 메일 서버가 없는 기본 설치라 mailConfigured도 false
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.mailConfigured").value(false));
        // Bob: 배정 알림 끔, 시작 화면 프로젝트
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifications\":{\"assigned\":false},\"startPage\":\"projects\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.assigned").value(false))
                .andExpect(jsonPath("$.notifications.commented").value(true))
                .andExpect(jsonPath("$.startPage").value("projects"));
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startPage\":\"mars\"}"))
                .andExpect(status().isBadRequest());

        // Alice가 Bob에게 배정 → Bob은 배정 알림을 받지 않는다
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"배정\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long issueId = JSON.readTree(issue).get("id").asLong();
        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));

        // Carol이 코멘트하면 자동 관찰(기본 on) → 이후 Alice의 코멘트 알림을 받는다
        mvc.perform(post("/api/alm/issues/{id}/comments", issueId).with(asUser(3, "Carol"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"참고\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/alm/issues/{id}/watchers", issueId).with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.watching").value(true));
        mvc.perform(post("/api/alm/issues/{id}/comments", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"답변\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/alm/notifications").with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("COMMENTED"));
    }

    @Test
    void 공지_배너는_관리자만_바꾸고_누구나_읽는다() throws Exception {
        mvc.perform(get("/api/alm/banner").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/alm/admin/banner").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"level\":\"warning\",\"message\":\"점검\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/alm/admin/banner").with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"level\":\"warning\",\"message\":\" 오늘 22시 점검 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("오늘 22시 점검"));
        mvc.perform(put("/api/alm/admin/banner").with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"level\":\"info\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/alm/banner").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.level").value("warning"));
    }
}
