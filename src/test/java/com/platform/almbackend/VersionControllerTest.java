package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectVersionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 버전(릴리스) 계약. 프로젝트별 버전과 이슈의 수정 버전(fix version), 릴리스·보관 전이.
 * 완료 판정은 프론트 워크플로 스킴 소유라 "미완료 이슈 이관"은 프론트가 대상 이슈 목록을 계산해
 * 개별 수정으로 옮기고, 서버는 버전 상태 전이와 소속 검증만 지킨다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class VersionControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired ProjectVersionRepository versions;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        versions.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        projectId = createProject("alm", "ALM 제품");
    }

    @Test
    void 버전을_만들고_목록으로_읽는다() throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/versions", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"1.0\",\"description\":\"첫 정식\",\"releaseDate\":\"2026-09-30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("1.0"))
                .andExpect(jsonPath("$.status").value("UNRELEASED"))
                .andExpect(jsonPath("$.releaseDate").value("2026-09-30"))
                .andExpect(jsonPath("$.releasedAt").doesNotExist());

        mvc.perform(get("/api/alm/projects/{id}/versions", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("1.0"));
    }

    @Test
    void 같은_이름의_버전은_409다() throws Exception {
        createVersion("1.0");

        mvc.perform(post("/api/alm/projects/{id}/versions", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"1.0\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 있는 버전 이름입니다: 1.0"));
    }

    @Test
    void 이슈에_수정_버전을_달고_다른_프로젝트_버전은_거부한다() throws Exception {
        long versionId = createVersion("1.0");
        long issueId = createIssue();

        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(versionId, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixVersionId").value(versionId));

        long otherProject = createProject("oth", "다른 제품");
        long foreign = createVersionIn(otherProject, "1.0");
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(foreign, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("다른 프로젝트의 버전입니다: " + foreign));
    }

    @Test
    void 릴리스하면_상태와_시각이_바뀌고_두_번은_안_된다() throws Exception {
        long versionId = createVersion("1.0");

        mvc.perform(post("/api/alm/versions/{id}/release", versionId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").exists());

        mvc.perform(post("/api/alm/versions/{id}/release", versionId).with(asUser(1, "Alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 릴리스된 버전입니다"));
    }

    @Test
    void 릴리스할_때_미완료_이슈를_다음_버전으로_옮긴다() throws Exception {
        long v1 = createVersion("1.0");
        long v2 = createVersion("1.1");
        long done = createIssue();
        long open = createIssue();
        mvc.perform(put("/api/alm/issues/{id}", done).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"done\","
                                + "\"priority\":\"MEDIUM\",\"details\":{\"fixVersionId\":" + v1 + "},\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/alm/issues/{id}", open).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(v1, 1)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/alm/versions/{id}/release", v1).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnresolvedToVersionId\":" + v2 + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mvc.perform(get("/api/alm/issues/{id}", open).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fixVersionId").value(v2));
        mvc.perform(get("/api/alm/issues/{id}", done).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fixVersionId").value(v1));

        // 릴리스된 버전으로는 이관할 수 없다
        long v3 = createVersion("1.2");
        mvc.perform(post("/api/alm/versions/{id}/release", v3).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"done\"],\"moveUnresolvedToVersionId\":" + v1 + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("릴리스된 버전으로는 이관할 수 없습니다"));
    }

    @Test
    void 보관된_버전에는_이슈를_달_수_없다() throws Exception {
        long versionId = createVersion("0.9");
        long issueId = createIssue();
        mvc.perform(post("/api/alm/versions/{id}/archive", versionId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(versionId, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("보관된 버전에는 이슈를 달 수 없습니다"));
    }

    @Test
    void 버전을_지우면_달려_있던_이슈의_수정_버전이_비워진다() throws Exception {
        long versionId = createVersion("1.0");
        long issueId = createIssue();
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(versionId, 1)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/alm/versions/{id}", versionId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fixVersionId").doesNotExist());
        mvc.perform(get("/api/alm/projects/{id}/versions", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 수정은_expectedVersion_충돌을_검증하고_날짜_역전을_거부한다() throws Exception {
        long versionId = createVersion("1.0");

        mvc.perform(put("/api/alm/versions/{id}", versionId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"1.0 GA\",\"startDate\":\"2026-10-01\",\"releaseDate\":\"2026-09-30\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("시작일은 릴리스일보다 늦을 수 없습니다"));

        mvc.perform(put("/api/alm/versions/{id}", versionId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"1.0 GA\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("1.0 GA"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(put("/api/alm/versions/{id}", versionId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"덮어쓰기\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 편집_권한이_없으면_버전을_만들_수_없다() throws Exception {
        permissions.setAllowed(false);

        mvc.perform(post("/api/alm/projects/{id}/versions", projectId).with(asUser(9, "Eve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"1.0\"}"))
                .andExpect(status().isForbidden());
    }

    private String updateBody(long fixVersionId, int expectedVersion) {
        return "{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\","
                + "\"priority\":\"MEDIUM\",\"details\":{\"fixVersionId\":" + fixVersionId
                + "},\"expectedVersion\":" + expectedVersion + "}";
    }

    private long createProject(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private long createVersion(String name) throws Exception {
        return createVersionIn(projectId, name);
    }

    private long createVersionIn(long targetProject, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/versions", targetProject).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    @Test
    void 이슈를_만들_때_수정_버전을_달_수_있고_다른_프로젝트_버전은_거부한다() throws Exception {
        long versionId = createVersion("1.0");
        String created = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\","
                                + "\"priority\":\"MEDIUM\",\"details\":{\"fixVersionId\":" + versionId + "}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fixVersionId").value(versionId))
                .andReturn().getResponse().getContentAsString();
        // 다시 읽어도 저장돼 있다
        long issueId = JSON.readTree(created).get("id").asLong();
        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fixVersionId").value(versionId));

        long otherProject = createProject("oth2", "또 다른 제품");
        long foreign = createVersionIn(otherProject, "1.0");
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\","
                                + "\"priority\":\"MEDIUM\",\"details\":{\"fixVersionId\":" + foreign + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("다른 프로젝트의 버전입니다: " + foreign));
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
