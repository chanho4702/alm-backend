package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.NotificationRepository;
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
 * 프로젝트 사이 권한 경계(2026-10-18 감사 A-01·A-02·A-03·A-17·A-18) — 링크·멘션·휴지통·댓글 정리·완료 판정.
 * Alice(1)는 두 프로젝트 모두, Bob(2)은 P1만 볼 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PermissionBoundaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository notifications;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;

    long p1;
    long p2;
    long p1Issue;
    long p2Issue;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        notifications.deleteAllInBatch();
        jdbc.update("delete from issue_link");
        jdbc.update("delete from issue_comment");
        jdbc.update("delete from worklog");
        jdbc.update("delete from issue_label");
        jdbc.update("delete from issue_component");
        jdbc.update("delete from issue");
        jdbc.update("delete from sprint");
        jdbc.update("delete from board");
        jdbc.update("delete from project");
        p1 = createProject("PA");
        p2 = createProject("PB");
        p1Issue = createIssue(p1, "공개 이슈");
        p2Issue = createIssue(p2, "비공개 프로젝트의 기밀 제목");
        jdbc.update("update issue set description = '기밀 본문' where id = ?", p2Issue);
        permissions.deny(2, p2);
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── A-01 링크 ──

    @Test
    void 볼_수_없는_프로젝트의_이슈로는_링크를_만들_수_없다() throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/links", p1Issue).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + p2Issue + ",\"type\":\"relates\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from issue_link", Long.class)).isZero();
    }

    @Test
    void 링크_목록은_볼_수_없는_반대편의_제목과_본문을_가린다() throws Exception {
        // Alice(두 프로젝트 모두 권한)가 만든 교차 링크
        mvc.perform(post("/api/alm/issues/{id}/links", p1Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + p2Issue + ",\"type\":\"relates\"}"))
                .andExpect(status().isCreated());

        String body = mvc.perform(get("/api/alm/issues/{id}/links", p1Issue).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].restricted").value(true))
                .andExpect(jsonPath("$[0].other.key").value("PB-1"))
                .andExpect(jsonPath("$[0].other.id").value(p2Issue))
                .andExpect(jsonPath("$[0].other.type").value("task"))
                .andExpect(jsonPath("$[0].other.status").value("todo"))
                // 허용 필드(id·key·type·status) 밖은 전부 중립값 — 우선순위·해결·버전·시각·프로젝트도 흘리지 않는다
                .andExpect(jsonPath("$[0].other.projectId").value(0))
                .andExpect(jsonPath("$[0].other.priority").doesNotExist())
                .andExpect(jsonPath("$[0].other.resolution").doesNotExist())
                .andExpect(jsonPath("$[0].other.version").value(0))
                .andExpect(jsonPath("$[0].other.order").value(0))
                .andExpect(jsonPath("$[0].other.reporterId").value(0))
                .andExpect(jsonPath("$[0].other.createdAt").doesNotExist())
                .andExpect(jsonPath("$[0].other.updatedAt").doesNotExist())
                .andExpect(jsonPath("$[0].other.archivedAt").doesNotExist())
                .andExpect(jsonPath("$[0].other.resolvedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("기밀");

        // 권한이 있으면 그대로 보인다
        mvc.perform(get("/api/alm/issues/{id}/links", p1Issue).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].restricted").value(false))
                .andExpect(jsonPath("$[0].other.title").value("비공개 프로젝트의 기밀 제목"))
                .andExpect(jsonPath("$[0].other.description").value("기밀 본문"));
    }

    // ── A-03 멘션 ──

    @Test
    void 프로젝트를_볼_수_없는_사용자는_멘션해도_알림을_받지_않는다() throws Exception {
        // Bob은 P2를 못 본다 — Alice가 P2 이슈 댓글에서 Bob과 Carol(3)을 멘션
        mvc.perform(post("/api/alm/issues/{id}/comments", p2Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"확인 부탁\",\"mentionedUserIds\":[2,3]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/notifications").with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("MENTIONED"));
    }

    @Test
    void 권한을_잃은_워처는_코멘트_알림을_받지_않는다() throws Exception {
        permissions.reset();
        mvc.perform(put("/api/alm/issues/{id}/watchers/me", p2Issue).with(asUser(2, "Bob")))
                .andExpect(status().isOk());
        permissions.deny(2, p2);
        mvc.perform(post("/api/alm/issues/{id}/comments", p2Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"진행 상황\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── A-02 휴지통 ──

    @Test
    void 휴지통_프로젝트의_이슈는_검색_AQL_조회_쓰기에서_사라진다() throws Exception {
        mvc.perform(delete("/api/alm/projects/{id}", p2).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());

        // 전역 범위 검색·AQL에서 빠진다
        mvc.perform(get("/api/alm/issues/search").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].key").value("PA-1"));
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "status").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"\"}"))
                .andExpect(jsonPath("$.total").value(1));

        // 범위가 명시된(전역 아님) 사용자도 마찬가지
        permissions.setAccessScope(com.platform.almbackend.permission.AccessScope.of(java.util.Set.of(p1, p2)));
        mvc.perform(get("/api/alm/issues/search").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"\"}"))
                .andExpect(jsonPath("$.total").value(1));

        // 직접 조회·키 조회·쓰기는 404
        mvc.perform(get("/api/alm/issues/{id}", p2Issue).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/issues/by-key/PB-1").with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/alm/issues/{id}/comments", p2Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"유령 댓글\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/alm/issues/{id}/worklogs", p2Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1,\"workedOn\":\"2026-10-18\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/alm/issues/{id}/links", p1Issue).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + p2Issue + ",\"type\":\"relates\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from issue_comment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from worklog", Long.class)).isZero();

        // 복원하면 다시 보인다
        mvc.perform(post("/api/alm/projects/{id}/restore", p2).with(asUser(1, "Alice")));
        mvc.perform(get("/api/alm/issues/{id}", p2Issue).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
    }

    // ── A-17 댓글·워크로그 정리 ──

    @Test
    void 프로젝트_관리자는_남의_댓글과_워크로그를_지울_수_있다() throws Exception {
        long comment = id(mvc.perform(post("/api/alm/issues/{id}/comments", p1Issue).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"부적절한 댓글\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long worklog = id(mvc.perform(post("/api/alm/issues/{id}/worklogs", p1Issue).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":2,\"workedOn\":\"2026-10-18\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        permissions.grantAdminOnly(1, p1);

        // 관리자가 아닌 제3자(Carol)는 여전히 못 지운다
        mvc.perform(delete("/api/alm/comments/{id}", comment).with(asUser(3, "Carol")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/alm/worklogs/{id}", worklog).with(asUser(3, "Carol")))
                .andExpect(status().isForbidden());
        // 관리자 수정은 안 된다 — 남의 글을 고치는 것은 작성자만
        mvc.perform(put("/api/alm/comments/{id}", comment).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"관리자 수정\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/alm/comments/{id}", comment).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/alm/worklogs/{id}", worklog).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 프로젝트_권한을_잃은_작성자는_자기_댓글도_고치지_못한다() throws Exception {
        long comment = id(mvc.perform(post("/api/alm/issues/{id}/comments", p1Issue).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"처음 글\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long worklog = id(mvc.perform(post("/api/alm/issues/{id}/worklogs", p1Issue).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":1,\"workedOn\":\"2026-10-18\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        permissions.deny(2, p1);

        mvc.perform(put("/api/alm/comments/{id}", comment).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"권한 없이 수정\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/alm/comments/{id}", comment).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/alm/worklogs/{id}", worklog).with(asUser(2, "Bob")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select body from issue_comment where id = ?", String.class, comment))
                .isEqualTo("처음 글");
    }

    // ── A-18 완료 판정 ──

    @Test
    void 스프린트_완료는_클라이언트가_보낸_doneStatuses가_아니라_서버_분류로_판정한다() throws Exception {
        long sprint = id(mvc.perform(post("/api/alm/projects/{id}/sprints", p1).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"S1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        jdbc.update("update issue set sprint_id = ?, status = 'todo' where id = ?", sprint, p1Issue);
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        // 낡은 클라이언트가 'todo'까지 완료로 보낸다 — 서버는 무시하고 미완료로 백로그에 돌린다
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprint).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"doneStatuses\":[\"todo\",\"done\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/issues/{id}", p1Issue).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprintId").doesNotExist());
    }

    @Test
    void 릴리스는_클라이언트가_보낸_doneStatuses가_아니라_서버_분류로_판정한다() throws Exception {
        long v1 = id(mvc.perform(post("/api/alm/projects/{id}/versions", p1).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"1.0\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long v2 = id(mvc.perform(post("/api/alm/projects/{id}/versions", p1).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"1.1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        jdbc.update("update issue set fix_version_id = ?, status = 'todo' where id = ?", v1, p1Issue);
        mvc.perform(post("/api/alm/versions/{id}/release", v1).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doneStatuses\":[\"todo\",\"done\"],\"moveUnresolvedToVersionId\":" + v2 + "}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/issues/{id}", p1Issue).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.fixVersionId").value(v2));
    }

    // ── 헬퍼 ──

    private long createProject(String key) throws Exception {
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createIssue(long projectId, String title) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static long id(String body) throws Exception {
        JsonNode node = JSON.readTree(body);
        return node.get("id").asLong();
    }
}
