package com.platform.almbackend;

import com.platform.almbackend.permission.AlmAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프로젝트 개발 설정(V43) — 브랜치 이름 접두: 기본값·저장·낙관적 락·git 참조 규칙·권한 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class DevSettingsTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "DEVS");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 저장한_적_없으면_접두_없음_version_0이고_저장하면_올라간다() throws Exception {
        mvc.perform(get("/api/alm/projects/{id}/settings/development", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchPrefix").value(""))
                .andExpect(jsonPath("$.version").value(0));

        // 앞뒤 공백·끝 /는 지운다
        save(" feature/team/ ", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.branchPrefix").value("feature/team"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/alm/projects/{id}/settings/development", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.branchPrefix").value("feature/team"));

        // 옛 version으로 저장하면 409
        save("fix", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error").isNotEmpty());
        // 빈 값 = 접두 없음
        save("", 1).andExpect(status().isOk()).andExpect(jsonPath("$.branchPrefix").value(""))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void git_참조_규칙에_걸리는_접두는_거절한다() throws Exception {
        for (String bad : new String[] {"my branch", "feat..x", "/feat", "a//b", ".hidden", "x.lock", "-x", "feat~1", "기능"}) {
            save(bad, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        }
        save("a".repeat(51), 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("브랜치 접두는 50자 이하여야 합니다"));
    }

    @Test
    void 쓰기는_프로젝트_관리자만() throws Exception {
        permissions.denyAction(2, projectId, AlmAction.ADMIN);
        mvc.perform(put("/api/alm/projects/{id}/settings/development", projectId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchPrefix\":\"feature\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alm/projects/{id}/settings/development", projectId).with(asUser(2, "Bob")))
                .andExpect(status().isOk());
    }

    private ResultActions save(String prefix, int expectedVersion) throws Exception {
        String escaped = prefix.replace("\\", "\\\\").replace("\"", "\\\"");
        return mvc.perform(put("/api/alm/projects/{id}/settings/development", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchPrefix\":\"" + escaped + "\",\"expectedVersion\":" + expectedVersion + "}"));
    }
}
