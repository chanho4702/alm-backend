package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
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

/** 우선순위 스킴 — 전역 레지스트리(5단계 + 커스텀), 프로젝트별 활성 목록·기본값, 이슈 생성 규칙 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PriorityControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired PriorityDefRepository priorities;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        priorities.findAll().stream().filter(p -> !p.isBuiltIn()).forEach(priorities::delete);
        seeder.resetToDefaults();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
    }

    private String issueJson(String priority) {
        return "{\"title\":\"이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\""
                + (priority == null ? "" : ",\"priority\":\"" + priority + "\"") + ",\"details\":{}}";
    }

    @Test
    void 기본_5단계가_순서대로_있고_우선순위_없이_만든_이슈는_보통이며_대소문자를_가리지_않는다() throws Exception {
        mvc.perform(get("/api/alm/settings/priorities").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].id").value("highest"))
                .andExpect(jsonPath("$[2].id").value("medium"))
                .andExpect(jsonPath("$[4].builtIn").value(true));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("medium"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson("HIGHEST")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("highest"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson("urgent")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("없는 우선순위입니다: urgent"));
    }

    @Test
    void 관리자가_커스텀_우선순위를_만들고_순서를_옮기고_쓰이지_않으면_지운다() throws Exception {
        mvc.perform(post("/api/alm/settings/priorities").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"긴급\",\"icon\":\"flag\",\"color\":\"danger\"}"))
                .andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/alm/settings/priorities").with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"긴급\",\"icon\":\"flag\",\"color\":\"danger\",\"description\":\"장애\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order").value(6))
                .andExpect(jsonPath("$.builtIn").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JSON.readTree(created).get("id").asText();
        mvc.perform(post("/api/alm/settings/priorities").with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"긴급\",\"icon\":\"flag\",\"color\":\"danger\"}"))
                .andExpect(status().isBadRequest());
        // 맨 위로 올리기(5칸)
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/alm/settings/priorities/{id}/move", id).with(asAdmin(1, "Alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1}"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/alm/settings/priorities").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[1].id").value("highest"));
        // 스킴에서 활성화하지 않았으니 이슈에 쓸 수 없다
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson(id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 프로젝트에서 사용할 수 없는 우선순위입니다: 긴급"));
        mvc.perform(delete("/api/alm/settings/priorities/highest").with(asAdmin(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("기본 우선순위는 삭제할 수 없습니다"));
        mvc.perform(delete("/api/alm/settings/priorities/{id}", id).with(asAdmin(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/settings/priorities").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].order").value(1));
    }

    @Test
    void 프로젝트_커스텀_설정에서_활성_우선순위와_기본값을_바꾸면_생성에_적용된다() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}"))
                .andExpect(status().isOk());
        String resolved = mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.body.enabledPriorities.length()").value(5))
                .andExpect(jsonPath("$.body.defaultPriority").value("medium"))
                .andReturn().getResponse().getContentAsString();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(resolved).get("body");
        body.putArray("enabledPriorities").add("high").add("low");
        body.put("defaultPriority", "low");
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.defaultPriority").value("low"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("low"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(issueJson("medium")))
                .andExpect(status().isBadRequest());
        // 기본값이 활성 목록 밖이면 거부
        body.put("defaultPriority", "highest");
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("기본 우선순위는 활성화된 우선순위 중에서 골라야 합니다"));
    }
}
