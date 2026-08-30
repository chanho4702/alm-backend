package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.ComponentRepository;
import com.platform.almbackend.repository.IssueRepository;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 컴포넌트 — 프로젝트별 CRUD, 이슈 지정·검색 필터, 컴포넌트 기본 담당자가 프로젝트 규칙보다 우선 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ComponentControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired ComponentRepository components;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        components.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
    }

    private long createComponent(String json) throws Exception {
        String created = mvc.perform(post("/api/alm/projects/{id}/components", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(created).get("id").asLong();
    }

    @Test
    void 컴포넌트를_만들고_이슈에_붙이고_검색으로_거른다() throws Exception {
        long api = createComponent("{\"name\":\"API\",\"description\":\"백엔드\"}");
        long ui = createComponent("{\"name\":\"UI\"}");
        mvc.perform(post("/api/alm/projects/{id}/components", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"API\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("컴포넌트 이름이 중복됩니다: API"));

        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API 이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{\"componentIds\":[" + api + "," + api + "]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.componentIds.length()").value(1))
                .andExpect(jsonPath("$.componentIds[0]").value(api))
                .andReturn().getResponse().getContentAsString();
        long issueId = JSON.readTree(issue).get("id").asLong();
        int version = JSON.readTree(issue).get("version").asInt();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"UI 이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{\"componentIds\":[" + ui + "]}}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId))
                        .param("componentIds", String.valueOf(api)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("API 이슈"));
        mvc.perform(get("/api/alm/projects/{id}/components", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("API"))
                .andExpect(jsonPath("$[0].issueCount").value(1));

        // 수정으로 컴포넌트 교체 → 검색에서 빠진다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API 이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"medium\",\"expectedVersion\":" + version + ",\"details\":{\"componentIds\":[" + ui + "]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentIds[0]").value(ui));
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId))
                        .param("componentIds", String.valueOf(api)).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(0));

        // 삭제하면 이슈에서 떨어진다
        mvc.perform(delete("/api/alm/components/{id}", ui).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.componentIds.length()").value(0));
    }

    @Test
    void 컴포넌트_기본_담당자가_프로젝트_규칙보다_우선한다() throws Exception {
        long api = createComponent("{\"name\":\"API\",\"leadId\":7,\"defaultAssignee\":\"lead\"}");
        long none = createComponent("{\"name\":\"기타\",\"defaultAssignee\":\"unassigned\"}");
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{\"componentIds\":[" + api + "]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeId").value(7));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"b\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{\"componentIds\":[" + none + "," + api + "]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeId").doesNotExist());
        mvc.perform(put("/api/alm/components/{id}", api).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultAssignee\":\"boss\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/alm/components/{id}", api).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"API v2\",\"clearLead\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API v2"))
                .andExpect(jsonPath("$.leadId").doesNotExist());
    }
}
