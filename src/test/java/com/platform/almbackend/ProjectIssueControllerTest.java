package com.platform.almbackend;

import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.almbackend.TestAuth.asUser;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ProjectIssueControllerTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.RecordingEventPublisher events;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        events.clear();
    }

    @Test
    void 프로젝트와_이슈를_만들고_이벤트를_발행한다() throws Exception {
        String project = mvc.perform(post("/api/alm/projects")
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"alm","name":"ALM 제품","description":"통합 이슈 관리"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ALM"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long projectId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(project).get("id").asLong();

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"로그인 오류","description":"OIDC callback 실패","type":"BUG","status":"todo","priority":"HIGH",
                                 "details":{"dueDate":"2026-08-20","estimateHours":3.5,"labels":[" security ","backend","security"]}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ALM-1"))
                .andExpect(jsonPath("$.reporterId").value(1))
                .andExpect(jsonPath("$.dueDate").value("2026-08-20"))
                .andExpect(jsonPath("$.estimateHours").value(3.5))
                .andExpect(jsonPath("$.labels[0]").value("security"))
                .andExpect(jsonPath("$.labels[1]").value("backend"))
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.order").value(1))
                .andExpect(jsonPath("$.version").value(1));

        org.assertj.core.api.Assertions.assertThat(permissions.grantedProjectId()).isEqualTo(projectId);
        org.assertj.core.api.Assertions.assertThat(events.events())
                .extracting(EventEnvelope -> EventEnvelope.getPayloadCase().name())
                .containsExactly("PROJECT_CREATED", "ISSUE_CREATED");
    }

    @Test
    void 이슈_수정은_expectedVersion을_검사한다() throws Exception {
        long projectId = createProject();
        long issueId = createIssue(projectId);

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수정","description":"본문","type":"TASK","status":"inprogress","priority":"MEDIUM","assigneeId":2,"expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"덮어쓰기","description":"본문","type":"TASK","status":"done","priority":"LOW","expectedVersion":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(endsWith("현재 2, 요청 1")));
    }

    @Test
    void V1_PUT은_확장값을_보존하고_details는_명시적으로_바꾼다() throws Exception {
        long projectId = createProject();
        long issueId = createIssue(projectId, """
                {"title":"상세 필드","type":"TASK",
                 "details":{"dueDate":"2026-08-20","estimateHours":2.5,"labels":["backend"]}}
                """);

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"V1 수정","description":"본문","type":"TASK","status":"todo","priority":"MEDIUM","expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-08-20"))
                .andExpect(jsonPath("$.estimateHours").value(2.5))
                .andExpect(jsonPath("$.labels[0]").value("backend"))
                .andExpect(jsonPath("$.order").value(1))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(put("/api/alm/issues/{id}", issueId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"확장 해제","description":"본문","type":"TASK","status":"todo","priority":"MEDIUM",
                                 "details":{"parentId":null,"dueDate":null,"estimateHours":null,"labels":[]},"expectedVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.estimateHours").doesNotExist())
                .andExpect(jsonPath("$.labels.length()").value(0))
                .andExpect(jsonPath("$.order").value(1))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void 에픽_일반이슈_하위작업의_2단계_계층만_허용한다() throws Exception {
        long projectId = createProject();
        long epicId = createIssue(projectId, "{\"title\":\"에픽\",\"type\":\"EPIC\"}");
        long storyId = createIssue(projectId, """
                {"title":"스토리","type":"STORY","details":{"parentId":%d}}
                """.formatted(epicId));
        long subtaskId = createIssue(projectId, """
                {"title":"하위 작업","type":"SUBTASK","details":{"parentId":%d}}
                """.formatted(storyId));

        mvc.perform(get("/api/alm/issues/{id}", subtaskId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(storyId));

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"잘못된 하위 작업","type":"SUBTASK","details":{"parentId":%d}}
                                """.formatted(epicId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이슈 타입에 맞지 않는 부모입니다"));

        long otherProjectId = createProject("OTH");
        mvc.perform(post("/api/alm/projects/{id}/issues", otherProjectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"다른 프로젝트 자식","type":"STORY","details":{"parentId":%d}}
                                """.formatted(epicId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이슈 타입에 맞지 않는 부모입니다"));
    }

    @Test
    void 생성_순서대로_order를_발급하고_목록도_그_순서를_지킨다() throws Exception {
        long projectId = createProject();
        createIssue(projectId, "{\"title\":\"첫 번째\"}");
        createIssue(projectId, "{\"title\":\"두 번째\"}");

        mvc.perform(get("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("첫 번째"))
                .andExpect(jsonPath("$[0].order").value(1))
                .andExpect(jsonPath("$[1].title").value("두 번째"))
                .andExpect(jsonPath("$[1].order").value(2));
    }

    @Test
    void 부모_삭제는_자식의_parentId를_해제한다() throws Exception {
        long projectId = createProject();
        long epicId = createIssue(projectId, "{\"title\":\"에픽\",\"type\":\"EPIC\"}");
        long storyId = createIssue(projectId, """
                {"title":"스토리","type":"STORY","details":{"parentId":%d}}
                """.formatted(epicId));

        mvc.perform(delete("/api/alm/issues/{id}", epicId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/issues/{id}", storyId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void 자식이_있으면_계층을_깨는_타입_변경을_거부한다() throws Exception {
        long projectId = createProject();
        long epicId = createIssue(projectId, "{\"title\":\"에픽\",\"type\":\"EPIC\"}");
        createIssue(projectId, """
                {"title":"스토리","type":"STORY","details":{"parentId":%d}}
                """.formatted(epicId));

        mvc.perform(put("/api/alm/issues/{id}", epicId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"일반 작업으로 변경","description":"","type":"TASK","status":"todo","priority":"MEDIUM","expectedVersion":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("하위 이슈가 있어 타입을 변경할 수 없습니다"));
    }

    @Test
    void 타입_변경으로_기존_부모가_부적합해지면_자동_해제한다() throws Exception {
        long projectId = createProject();
        long epicId = createIssue(projectId, "{\"title\":\"에픽\",\"type\":\"EPIC\"}");
        long storyId = createIssue(projectId, """
                {"title":"스토리","type":"STORY","details":{"parentId":%d}}
                """.formatted(epicId));

        mvc.perform(put("/api/alm/issues/{id}", storyId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"하위 작업으로 변경","description":"","type":"SUBTASK","status":"todo","priority":"MEDIUM","expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("subtask"))
                .andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void 예상_시간은_0보다_커야_한다() throws Exception {
        long projectId = createProject();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"잘못된 예상 시간","details":{"estimateHours":0}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("예상 시간은 0보다 커야 합니다"));
    }

    @Test
    void 권한이_없으면_이슈_조회와_쓰기를_거부한다() throws Exception {
        long projectId = createProject();
        permissions.setAllowed(false);

        mvc.perform(get("/api/alm/projects/{id}/issues", projectId).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"금지\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/alm/projects"))
                .andExpect(status().isUnauthorized());
    }

    private long createProject() throws Exception {
        return createProject("TST");
    }

    private long createProject(String key) throws Exception {
        String body = mvc.perform(post("/api/alm/projects")
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"테스트\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    private long createIssue(long projectId) throws Exception {
        return createIssue(projectId, "{\"title\":\"첫 이슈\"}");
    }

    private long createIssue(long projectId, String request) throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId)
                        .with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }
}
