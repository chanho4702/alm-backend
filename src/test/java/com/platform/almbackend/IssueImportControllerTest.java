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

/** 이관 가져오기 계약 — 키 보존, 카운터 앞당김, 행 단위 실패 분리. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueImportControllerTest {

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
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"기존\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 키를_보존해_만들고_중복이나_형식_오류는_행_실패로_남긴다() throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":["
                                + "{\"key\":\"alm-20\",\"title\":\"이관 이슈\",\"type\":\"BUG\",\"status\":\"done\",\"priority\":\"HIGH\",\"details\":{\"labels\":[\"legacy\"]}},"
                                + "{\"key\":\"ALM-1\",\"title\":\"중복 키\"},"
                                + "{\"key\":\"PAY-3\",\"title\":\"다른 프로젝트 키\"},"
                                + "{\"title\":\"\"},"
                                + "{\"title\":\"키 없는 이슈\"}"
                                + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed.length()").value(3))
                .andExpect(jsonPath("$.failed[0].row").value(2))
                .andExpect(jsonPath("$.failed[0].reason").value("이미 있는 키입니다: ALM-1"))
                .andExpect(jsonPath("$.failed[1].reason").value("키는 ALM-번호 형식이어야 합니다: PAY-3"))
                .andExpect(jsonPath("$.failed[2].reason").value("이슈 제목을 입력하세요"))
                .andReturn().getResponse().getContentAsString();
        JSON.readTree(body);

        mvc.perform(get("/api/alm/issues/by-key/{key}", "ALM-20").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.labels[0]").value("legacy"));
        // 카운터가 20을 넘어섰다 — 키 없는 이슈는 ALM-21
        mvc.perform(get("/api/alm/issues/by-key/{key}", "ALM-21").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("키 없는 이슈"));
    }

    @Test
    void 편집_권한이_없으면_403이고_빈_목록은_400() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        permissions.setAllowed(false);
        mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"title\":\"x\"}]}"))
                .andExpect(status().isForbidden());
    }
}
