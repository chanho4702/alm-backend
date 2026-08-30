package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 서버 검색·페이징 계약 — 조건 AND, 라벨은 하나라도 겹치면, 정렬은 우선순위 의미 순, 키 단건 조회. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueSearchControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
        create("로그인 버그", "BUG", "todo", "HIGH", 2L, "[\"backend\",\"auth\"]");
        create("보드 개선", "STORY", "inprogress", "MEDIUM", null, "[\"frontend\"]");
        create("문서 정리", "TASK", "done", "LOW", 2L, "[]");
        create("검색 느림", "BUG", "todo", "LOW", 3L, "[\"backend\"]");
    }

    private void create(String title, String type, String status, String priority, Long assignee, String labels)
            throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"설명\",\"type\":\"" + type
                                + "\",\"status\":\"" + status + "\",\"priority\":\"" + priority
                                + "\",\"assigneeId\":" + assignee + ",\"details\":{\"labels\":" + labels + "}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 조건은_AND로_묶이고_라벨은_하나라도_겹치면_잡힌다() throws Exception {
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(projectId))
                        .param("types", "BUG").param("statuses", "todo").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));

        mvc.perform(get("/api/alm/issues/search").param("labels", "auth", "frontend").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/alm/issues/search").param("text", "느림").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("검색 느림"));

        // 미지정 + 특정 담당자
        mvc.perform(get("/api/alm/issues/search").param("assignees", "unassigned", "3").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void 우선순위_정렬은_의미_순이고_페이징은_total을_유지한다() throws Exception {
        mvc.perform(get("/api/alm/issues/search").param("sort", "priority").param("dir", "asc")
                        .param("size", "2").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.items[1].priority").value("MEDIUM"));
        mvc.perform(get("/api/alm/issues/search").param("sort", "priority").param("dir", "asc")
                        .param("size", "2").param("page", "1").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].priority").value("LOW"));
    }

    @Test
    void 키로_단건을_찾고_권한이_없으면_403() throws Exception {
        mvc.perform(get("/api/alm/issues/by-key/{key}", "alm-2").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("ALM-2"))
                .andExpect(jsonPath("$.title").value("보드 개선"));
        mvc.perform(get("/api/alm/issues/by-key/{key}", "ALM-99").with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        permissions.setAllowed(false);
        mvc.perform(get("/api/alm/issues/by-key/{key}", "ALM-2").with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());
    }
}
