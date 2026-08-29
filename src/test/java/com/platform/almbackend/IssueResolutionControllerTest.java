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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 해결(Resolution) 계약. "왜 끝났는가"(완료됨/하지 않음/중복/재현 불가)를 서버가 보존한다.
 * 완료 카테고리 판정은 워크플로 스킴을 가진 프론트 소유라, 서버는 값의 유효성만 지키고
 * 기본값·해제 규칙은 프론트가 적용해 보낸다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueResolutionControllerTest {

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
    }

    @Test
    void 해결을_저장하고_비울_수_있다() throws Exception {
        long issueId = createIssue();

        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("done", "\"WONT_DO\"", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("WONT_DO"));

        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.resolution").value("WONT_DO"));

        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("todo", "null", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").doesNotExist());
    }

    @Test
    void 생성_직후에는_해결이_없다() throws Exception {
        long issueId = createIssue();

        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.resolution").doesNotExist());
    }

    @Test
    void 정의되지_않은_해결_값은_거부한다() throws Exception {
        long issueId = createIssue();

        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("done", "\"MAYBE\"", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void details_없이_수정하면_기존_해결을_보존한다() throws Exception {
        long issueId = createIssue();
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("done", "\"DUPLICATE\"", 1)))
                .andExpect(status().isOk());

        // V1 클라이언트처럼 details를 아예 빼고 보낸다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"done\",\"priority\":\"MEDIUM\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("DUPLICATE"));
    }

    private String updateBody(String status, String resolutionJson, int expectedVersion) {
        return "{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"" + status
                + "\",\"priority\":\"MEDIUM\",\"details\":{\"resolution\":" + resolutionJson
                + "},\"expectedVersion\":" + expectedVersion + "}";
    }

    private long createIssue() throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }
}
