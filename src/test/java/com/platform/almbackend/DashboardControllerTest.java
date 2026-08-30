package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.DashboardRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.WorklogRepository;
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

/** 대시보드 — 내 것 + 공유, 소유자만 수정, 가젯 JSON은 배열만. 프로젝트 워크로그는 기간으로 거른다 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class DashboardControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired DashboardRepository dashboards;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired WorklogRepository worklogs;
    @Autowired TestConfig.FakePermissionClient permissions;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        dashboards.deleteAllInBatch();
        worklogs.deleteAllInBatch();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
    }

    @Test
    void 내_대시보드와_공유된_대시보드만_보이고_소유자만_고친다() throws Exception {
        String mine = mvc.perform(post("/api/alm/dashboards").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"내 보드\",\"gadgets\":[{\"id\":\"g1\",\"type\":\"status-distribution\",\"column\":0,\"config\":{}}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gadgets[0].type").value("status-distribution"))
                .andReturn().getResponse().getContentAsString();
        long mineId = JSON.readTree(mine).get("id").asLong();
        mvc.perform(post("/api/alm/dashboards").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"팀 보드\",\"shared\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/alm/dashboards").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"gadgets\":{\"not\":\"array\"}}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/alm/dashboards").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("팀 보드"));
        mvc.perform(get("/api/alm/dashboards/{id}", mineId).with(asUser(2, "Bob")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/alm/dashboards/{id}", mineId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"훔침\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/alm/dashboards/{id}", mineId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shared\":true,\"gadgets\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.gadgets.length()").value(0));
        mvc.perform(get("/api/alm/dashboards").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete("/api/alm/dashboards/{id}", mineId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 프로젝트_워크로그는_기간으로_거르고_이슈_키를_붙인다() throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long issueId = JSON.readTree(issue).get("id").asLong();
        for (String[] w : new String[][] {{"2026-08-01", "2"}, {"2026-08-20", "3.5"}, {"2026-08-30", "1"}}) {
            mvc.perform(post("/api/alm/issues/{id}/worklogs", issueId).with(asUser(1, "Alice"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hours\":" + w[1] + ",\"comment\":\"\",\"workedOn\":\"" + w[0] + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/alm/projects/{id}/worklogs", projectId).param("since", "2026-08-15").param("until", "2026-08-31")
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].issueKey").value("ALM-1"))
                .andExpect(jsonPath("$[0].hours").value(3.5));
        mvc.perform(get("/api/alm/projects/{id}/worklogs", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(3));
    }
}
