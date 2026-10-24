package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장된 보기(D1, V31) — 표시 옵션 검증·정규화, 개인/프로젝트 공유 범위와 권한(저장 필터 공유 모델과 같은 결),
 * 낙관적 락.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SavedViewControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "VW");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 비운_표시_옵션은_기본값으로_채워_돌려준다() throws Exception {
        mvc.perform(post("/api/alm/views").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  전체  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("전체"))
                .andExpect(jsonPath("$.scope").value("personal"))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.layout").value("list"))
                .andExpect(jsonPath("$.filter").doesNotExist())
                .andExpect(jsonPath("$.display.groupBy").value("none"))
                .andExpect(jsonPath("$.display.orderBy").value("manual"))
                .andExpect(jsonPath("$.display.orderDirection").value("asc"))
                .andExpect(jsonPath("$.display.properties[0]").value("key"))
                .andExpect(jsonPath("$.display.showSubIssues").value(true))
                .andExpect(jsonPath("$.display.showEmptyGroups").value(false))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void 모르는_표시_값과_틀린_AQL은_저장을_막는다() throws Exception {
        expect400("{\"name\":\"x\",\"display\":{\"groupBy\":\"mood\"}}", "모르는 그룹 기준입니다: mood");
        expect400("{\"name\":\"x\",\"display\":{\"groupBy\":\"status\",\"subGroupBy\":\"status\"}}",
                "하위 그룹 기준은 그룹 기준과 달라야 합니다");
        expect400("{\"name\":\"x\",\"display\":{\"properties\":[\"key\",\"color\"]}}", "모르는 표시 속성입니다: color");
        expect400("{\"name\":\"x\",\"layout\":\"gantt\"}", "보기 레이아웃은 list 또는 board입니다");
        expect400("{\"name\":\"x\",\"scope\":\"project\"}", "프로젝트 보기에는 프로젝트가 필요합니다");
        mvc.perform(post("/api/alm/views").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"filter\":{\"kind\":\"aql\",\"query\":\"status = \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.position").exists());
    }

    @Test
    void 프로젝트_보기는_열람자_모두가_보고_만든_사람이나_관리자만_고친다() throws Exception {
        JsonNode view = create(1, "{\"name\":\"막힌 이슈\",\"scope\":\"project\",\"projectId\":" + projectId
                + ",\"layout\":\"board\",\"filter\":{\"kind\":\"aql\",\"query\":\"flagged = true\"},"
                + "\"display\":{\"groupBy\":\"assignee\",\"orderBy\":\"storyPoints\",\"orderDirection\":\"desc\","
                + "\"properties\":[\"key\",\"storyPoints\",\"key\"]}}");
        long id = view.get("id").asLong();
        assertThat(view.get("display").get("properties")).hasSize(2); // 중복은 한 번만
        // Bob(열람자)의 개인 보기 하나
        permissions.denyAction(2, projectId, AlmAction.EDIT);
        create(2, "{\"name\":\"내 것\",\"projectId\":" + projectId + "}");

        // Bob: 프로젝트 보기(먼저) + 자기 개인 보기
        mvc.perform(get("/api/alm/projects/{id}/views", projectId).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("막힌 이슈"))
                .andExpect(jsonPath("$[0].mine").value(false))
                .andExpect(jsonPath("$[0].canEdit").value(false))
                .andExpect(jsonPath("$[0].filter.query").value("flagged = true"))
                .andExpect(jsonPath("$[1].name").value("내 것"))
                .andExpect(jsonPath("$[1].canEdit").value(true));
        // Alice의 목록에는 Bob의 개인 보기가 없다
        mvc.perform(get("/api/alm/projects/{id}/views", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));

        // 볼 수 있는 남의 공유 보기 수정은 403(없다고 하지 않는다)
        mvc.perform(put("/api/alm/views/{id}", id).with(asUser(2, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"뺏기\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("공유된 보기는 만든 사람이나 프로젝트 관리자만 수정·삭제할 수 있습니다"));

        // 프로젝트 관리자(사용자 3)는 남의 공유 보기를 고친다
        permissions.grantAdminOnly(3, projectId);
        mvc.perform(put("/api/alm/views/{id}", id).with(asUser(3, "Carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"막힘\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("막힘"))
                .andExpect(jsonPath("$.layout").value("board"))
                .andExpect(jsonPath("$.display.orderBy").value("storyPoints"))
                .andExpect(jsonPath("$.version").value(2));

        // 오래된 버전으로 저장하면 409
        mvc.perform(put("/api/alm/views/{id}", id).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"늦은 저장\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());

        // 프로젝트를 볼 수 없는 사람에게는 없는 보기다
        permissions.deny(4, projectId);
        mvc.perform(get("/api/alm/views/{id}", id).with(asUser(4, "Dave"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/projects/{id}/views", projectId).with(asUser(4, "Dave"))).andExpect(status().isForbidden());
    }

    @Test
    void 개인_보기는_남에게_404이고_열람자는_프로젝트_보기를_만들_수_없다() throws Exception {
        long mine = create(1, "{\"name\":\"비밀\"}").get("id").asLong();
        mvc.perform(get("/api/alm/views/{id}", mine).with(asUser(2, "Bob"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/alm/views/{id}", mine).with(asUser(2, "Bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/me/views").with(asUser(2, "Bob"))).andExpect(jsonPath("$.length()").value(0));

        permissions.denyAction(2, projectId, AlmAction.EDIT);
        mvc.perform(post("/api/alm/views").with(asUser(2, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"팀 보기\",\"scope\":\"project\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 개인_보기를_프로젝트에_공유하고_필터를_지우고_삭제한다() throws Exception {
        JsonNode view = create(1, "{\"name\":\"내 보드\",\"projectId\":" + projectId
                + ",\"filter\":{\"kind\":\"smart\",\"query\":\"담당자:나\"}}");
        long id = view.get("id").asLong();
        mvc.perform(put("/api/alm/views/{id}", id).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"project\",\"clearFilter\":true,\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("project"))
                .andExpect(jsonPath("$.filter").doesNotExist())
                .andExpect(jsonPath("$.name").value("내 보드"));

        // 프로젝트가 없는 보기는 공유할 수 없다
        long global = create(1, "{\"name\":\"전체\"}").get("id").asLong();
        mvc.perform(put("/api/alm/views/{id}", global).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"project\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/alm/views/{id}", id).with(asUser(1, "Alice"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/views/{id}", id).with(asUser(1, "Alice"))).andExpect(status().isNotFound());
    }

    private JsonNode create(long user, String body) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/alm/views").with(asUser(user, "U" + user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void expect400(String body, String message) throws Exception {
        mvc.perform(post("/api/alm/views").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(message));
    }
}
