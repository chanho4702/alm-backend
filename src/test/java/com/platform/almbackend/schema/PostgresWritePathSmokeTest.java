package com.platform.almbackend.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static com.platform.almbackend.TestAuth.asAdmin;
import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL 16 + Flyway 스키마(ddl-auto=validate) 위에서 핵심 쓰기·조회 경로를 HTTP로 한 바퀴 돈다
 * (2026-10-18 감사 A-05). 다른 테스트는 H2 + Hibernate DDL이라 "H2는 통과, PG는 500" 계열 결함 — 시각 null
 * 파라미터 타입 추론(87257fc), 부분 유니크 인덱스, 재귀 CTE, 네이티브 질의 방언 — 을 못 잡는다.
 *
 * <p>CI(`./gradlew build`)의 test 태스크가 그대로 돌린다(ubuntu runner에 Docker가 있다). 테스트끼리는 프로젝트 키를
 * 달리해 데이터를 지우지 않고 공존한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
class PostgresWritePathSmokeTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger KEYS = new AtomicInteger();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 이슈_생성_수정_충돌_이동_랭크가_PG에서_돈다() throws Exception {
        long project = createProject();
        long sprint = id(mvc.perform(post("/api/alm/projects/{id}/sprints", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"S1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long a = createIssue(project, "첫 이슈", "{\"labels\":[\"ui\",\"api\"],\"sprintId\":" + sprint + "}", 7L);
        long b = createIssue(project, "둘째 이슈", "{\"sprintId\":" + sprint + "}", null);

        int version = version(a);
        mvc.perform(put("/api/alm/issues/{id}", a).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("첫 이슈 수정", "inprogress", version, "{\"labels\":[\"ui\"],\"sprintId\":" + sprint + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1))
                .andExpect(jsonPath("$.labels.length()").value(1));
        // 같은 버전으로 다시 저장하면 409 — 잠근 뒤 다시 읽은 값으로 비교한다
        mvc.perform(put("/api/alm/issues/{id}", a).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("덮어쓰기", "todo", version, "{\"labels\":[]}")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/alm/issues/{id}/move", b).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"inprogress\",\"beforeId\":" + a + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inprogress"));
        mvc.perform(post("/api/alm/issues/{id}/rank", b).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprintId").doesNotExist());

        // 진행 중 스프린트 시작(부분 유니크 인덱스) → 보드(라벨 EXISTS·담당자 IN)
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        long board = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/boards", project).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        mvc.perform(put("/api/alm/boards/{id}", board).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"assigneeIds\":[\"7\",\"unassigned\"],\"types\":[\"task\"],\"labels\":[\"ui\"]}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/boards/{id}/issues", board).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(a));

        // 계층 순환 검사(재귀 CTE)
        long child = createIssue(project, "하위", "{\"parentId\":" + a + "}", null);
        mvc.perform(put("/api/alm/issues/{id}", a).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("첫 이슈 수정", "inprogress", version(a), "{\"parentId\":" + child + ",\"labels\":[\"ui\"]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상위 항목이 순환합니다"));

        // 일괄 편집 — 행별 트랜잭션
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + b + "},{\"issueId\":" + child + ",\"expectedVersion\":999}],"
                                + "\"changes\":{\"priority\":\"high\",\"addLabels\":[\"bulk\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.results[1].code").value("CONFLICT"));
    }

    @Test
    void 검색_집계_AQL_라벨이_PG에서_돈다() throws Exception {
        long project = createProject();
        createIssue(project, "결제 100% 실패", "{\"labels\":[\"pay\"]}", 7L);
        createIssue(project, "로그인 지연", "{}", null);
        createIssue(project, "snake_case 키", "{}", 7L);

        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(project)).param("text", "%")
                        .with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(project))
                        .param("assignees", "7").param("labels", "pay").param("sort", "priority")
                        .param("updatedSince", "2020-01-01T00:00:00Z").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(get("/api/alm/issues/search").param("projectIds", String.valueOf(project)).param("size", "2")
                        .param("sort", "status").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.truncated").value(true));
        mvc.perform(get("/api/alm/issues/aggregate").param("projectIds", String.valueOf(project))
                        .param("groupBy", "statusCategory").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/alm/issues/aggregate").param("projectIds", String.valueOf(project))
                        .param("groupBy", "assignee").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/alm/projects/{id}/labels", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0]").value("pay"));
        String key = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}", project).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString()).get("key").asText();
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"project = " + key + " AND text ~ \\\"%\\\" ORDER BY created DESC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/alm/projects/statuses").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + project + "'][0].kind").exists());
    }

    @Test
    void 변경_이력과_감사_로그의_시각_필터가_PG에서_돈다() throws Exception {
        long project = createProject();
        createIssue(project, "이력", "{}", null);
        mvc.perform(get("/api/alm/projects/{id}/changes", project).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().string("X-Result-Truncated", "false"));
        mvc.perform(get("/api/alm/projects/{id}/changes", project).param("since", "2020-01-01T00:00:00Z")
                        .param("until", "2999-01-01T00:00:00Z").param("field", "STATUS").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/alm/projects/{id}/changes", project).param("limit", "1").with(asUser(1, "Alice")))
                .andExpect(status().isOk());

        permissions.setGlobalAdmins(9);
        mvc.perform(get("/api/alm/admin/audit").with(asAdmin(9, "Root")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/admin/audit").param("since", "2020-01-01T00:00:00Z").with(asAdmin(9, "Root")))
                .andExpect(status().isOk());
    }

    @Test
    void 휴지통_프로젝트는_PG에서도_검색과_조회에서_빠진다() throws Exception {
        long project = createProject();
        long issue = createIssue(project, "버릴 것", "{}", null);
        mvc.perform(delete("/api/alm/projects/{id}", project).with(asUser(1, "Alice"))).andExpect(status().isNoContent());
        String all = mvc.perform(get("/api/alm/issues/search").param("size", "200").with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode item : JSON.readTree(all).get("items")) assertThat(item.get("id").asLong()).isNotEqualTo(issue);
        mvc.perform(get("/api/alm/issues/{id}", issue).with(asUser(1, "Alice"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"\",\"size\":200}"))
                .andExpect(status().isOk());
    }

    @Test
    void 예정_스프린트_삭제_이슈_복제_공유_필터가_PG에서_돈다() throws Exception {
        long project = createProject();
        long sprint = id(mvc.perform(post("/api/alm/projects/{id}/sprints", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"지울 것\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long issue = createIssue(project, "스프린트 안", "{\"sprintId\":" + sprint + ",\"labels\":[\"x\"]}", null);
        mvc.perform(delete("/api/alm/sprints/{id}", sprint).with(asUser(1, "Alice"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/issues/{id}", issue).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").doesNotExist());
        mvc.perform(post("/api/alm/issues/{id}/clone", issue).with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.labels[0]").value("x"));
        long filter = id(mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PG 공유\",\"kind\":\"smart\",\"query\":\"x\",\"shared\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/alm/filters/{id}", filter).with(asUser(2, "Bob"))).andExpect(status().isOk());
        mvc.perform(get("/api/alm/filters/shared").with(asUser(2, "Bob"))).andExpect(status().isOk());
    }

    @Test
    void 깊이_상한을_넘는_계층의_순환_검사는_PG에서도_거부로_끝난다() throws Exception {
        long project = createProject();
        long root = createIssue(project, "뿌리", "{}", null);
        long parent = root;
        for (int i = 0; i < 1100; i++) {
            parent = jdbc.queryForObject("""
                    insert into issue (project_id, issue_number, issue_key, title, description, issue_type, status, priority,
                                       reporter_id, sort_order, version, created_at, updated_at, parent_id)
                    select project_id, ?, project_id || '-D' || ?, title, description, issue_type, status, priority, reporter_id,
                           ?, 1, created_at, updated_at, ? from issue where id = ?
                    returning id
                    """, Long.class, 5000 + i, i, 5000 + i, parent, root);
        }
        mvc.perform(put("/api/alm/issues/{id}", root).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("뿌리", "todo", version(root), "{\"parentId\":" + parent + ",\"labels\":[]}")))
                .andExpect(status().isBadRequest());
        // 얕은 순환은 정확히 "순환"으로 잡는다
        long child = createIssue(project, "자식", "{\"parentId\":" + root + "}", null);
        mvc.perform(put("/api/alm/issues/{id}", root).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("뿌리", "todo", version(root), "{\"parentId\":" + child + ",\"labels\":[]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상위 항목이 순환합니다"));
    }

    // ── 헬퍼 ──

    private long createProject() throws Exception {
        String key = "PG" + KEYS.incrementAndGet();
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createIssue(long project, String title, String details, Long assigneeId) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\","
                                + "\"priority\":\"medium\",\"assigneeId\":" + assigneeId + ",\"details\":" + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static String updateBody(String title, String status, int expectedVersion, String details) {
        return "{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"" + status + "\","
                + "\"priority\":\"medium\",\"assigneeId\":7,\"details\":" + details + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private int version(long issueId) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString()).get("version").asInt();
    }

    private static long id(String body) throws Exception {
        return JSON.readTree(body).get("id").asLong();
    }
}
