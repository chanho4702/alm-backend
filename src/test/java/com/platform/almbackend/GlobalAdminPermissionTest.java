package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 전역 관리자 판정과 장애 전파 — 판정의 진실 소스는 org-service 하나다(2026-09-05).
 *
 * <p>지키려는 것 두 가지다. (1) Keycloak realm 역할 ADMIN을 들고 와도 org에 GLOBAL/ADMIN grant가
 * 없으면 못 한다 — 두 원장이 갈라지면 같은 사람에 대해 wiki와 ALM이 다른 답을 낸다.
 * (2) org가 죽었을 때 403이 아니라 503이다 — 장애를 권한 없음으로 오인하면 사용자가
 * "나는 관리자가 아니라는군" 하고 엉뚱한 조치를 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class GlobalAdminPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_ENDPOINT = "/api/alm/admin/stats";

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.reset();
    }

    @AfterEach
    void restore() {
        permissions.reset();
    }

    @Test
    void 전역_grant가_있으면_역할_없는_토큰도_관리자다() throws Exception {
        permissions.setGlobalAdmins(7);

        mvc.perform(get(ADMIN_ENDPOINT).with(asUser(7, "Root")))
                .andExpect(status().isOk());
    }

    @Test
    void Keycloak_ADMIN_역할만으로는_안_된다() throws Exception {
        permissions.setGlobalAdmins(); // org에는 아무 grant도 없다

        mvc.perform(get(ADMIN_ENDPOINT).with(asAdmin(9, "Root")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 관리자만 할 수 있습니다"));
    }

    @Test
    void 승인_대기_계정은_그_사실을_듣는다() throws Exception {
        permissions.setDeniedReason("PENDING");

        mvc.perform(get(ADMIN_ENDPOINT).with(asUser(3, "New")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));
    }

    @Test
    void 정지된_계정도_그_사실을_듣는다() throws Exception {
        permissions.setDeniedReason("SUSPENDED");

        mvc.perform(get(ADMIN_ENDPOINT).with(asUser(3, "Paused")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("정지된 계정입니다"));
    }

    @Test
    void 비활성된_계정도_그_사실을_듣는다() throws Exception {
        permissions.setDeniedReason("DEACTIVATED");

        mvc.perform(get(ADMIN_ENDPOINT).with(asUser(3, "Gone")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("비활성된 계정입니다"));
    }

    /** 모르는 사유는 일반 거부다 — proto의 사유 목록은 뒤에 늘 수 있다 */
    @Test
    void 모르는_사유는_일반_거부로_다룬다() throws Exception {
        permissions.setDeniedReason("SOMETHING_NEW");

        mvc.perform(get(ADMIN_ENDPOINT).with(asUser(3, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 관리자만 할 수 있습니다"));
    }

    @Test
    void org가_불능이면_403이_아니라_503이다() throws Exception {
        permissions.setGlobalAdmins(9);
        permissions.setUnavailable(true);

        mvc.perform(get(ADMIN_ENDPOINT).with(asAdmin(9, "Root")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));
    }

    @Test
    void 프로젝트_권한도_같은_거부_장애_구분을_따른다() throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"perm\",\"name\":\"권한\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();

        permissions.setAllowed(false);
        permissions.setDeniedReason("PENDING");
        mvc.perform(get("/api/alm/projects/{id}", projectId).with(asUser(3, "New")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));

        permissions.setDeniedReason("NO_GRANT");
        mvc.perform(get("/api/alm/projects/{id}", projectId).with(asUser(3, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("VIEW 권한이 필요합니다 (project " + projectId + ")"));

        permissions.setUnavailable(true);
        mvc.perform(get("/api/alm/projects/{id}", projectId).with(asUser(3, "Bob")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));
    }
}
