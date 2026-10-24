package com.platform.almbackend.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
import com.platform.almbackend.automation.AutomationScheduler;
import com.platform.almbackend.permission.AlmAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V30·V31·V35·V36·V37(웨이브 D·트리아지·자동화)을 실제 PostgreSQL 16 + Flyway 스키마(ddl-auto=validate)에서 한 바퀴 돈다 —
 * 합계 질의(sum/case 타입 섞임)·부분 인덱스·CHECK·예약 차지(조건부 UPDATE)가 H2와 다르게 굴지 않는지 본다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
class WaveDPostgresSmokeTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired AutomationScheduler scheduler;
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
    void 계획_필드_플래그_합계_속도_AQL이_PG에서_돈다() throws Exception {
        long project = project("PGD");
        long sprint = id(mvc.perform(post("/api/alm/projects/{id}/sprints", project).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"S1\"}")).andReturn().getResponse().getContentAsString());
        long done = issue(project, "{\"title\":\"끝\",\"description\":\"\",\"details\":{\"sprintId\":" + sprint
                + ",\"dueDate\":\"2026-11-10\",\"estimateHours\":1.5},\"planning\":{\"startDate\":\"2026-11-01\",\"storyPoints\":5}}");
        issue(project, "{\"title\":\"이월\",\"description\":\"\",\"details\":{\"sprintId\":" + sprint + "},\"planning\":{\"storyPoints\":2.5}}");
        mvc.perform(put("/api/alm/issues/{id}/flag", done).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"flagged\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/alm/issues/{id}/move", done).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"done\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/estimation/summary", project).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].storyPoints").value(7.5))
                .andExpect(jsonPath("$.groups[0].completedStoryPoints").value(5))
                .andExpect(jsonPath("$.groups[0].estimateHours").value(1.5));
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(get("/api/alm/projects/{id}/reports/velocity", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.sprints[0].completed").value(5))
                .andExpect(jsonPath("$.sprints[0].committed").value(7.5));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"project = PGD AND flagged = true AND startDate >= 2026-10-01 AND storyPoints > 1 ORDER BY storyPoints DESC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // DB도 시작일 > 마감일을 막는다(서비스 400 뒤의 받침)
        assertThatThrownBy(() -> jdbc.update("update issue set start_date = date '2026-12-01' where id = ?", done))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 보기_트리아지_자동화가_PG에서_돈다() throws Exception {
        long project = project("PGT");
        mvc.perform(post("/api/alm/views").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"보드\",\"scope\":\"project\",\"projectId\":" + project
                                + ",\"layout\":\"board\",\"display\":{\"groupBy\":\"status\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/alm/projects/{id}/views", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].display.groupBy").value("status"));

        // 생성 규칙 — 트리아지로 들어온 이슈에 코멘트
        mvc.perform(post("/api/alm/projects/{id}/automation/rules", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"접수 안내\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},"
                                + "\"condition\":\"triage = true\",\"actions\":[{\"type\":\"COMMENT\",\"body\":\"접수했습니다\"}]}"))
                .andExpect(status().isCreated());
        long scheduled = id(mvc.perform(post("/api/alm/projects/{id}/automation/rules", project).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"예약\",\"trigger\":{\"type\":\"SCHEDULED\",\"cron\":\"0 9 * * *\"},"
                                + "\"condition\":\"triage = true\",\"actions\":[{\"type\":\"SET_FIELD\",\"field\":\"flagged\",\"value\":true}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mvc.perform(put("/api/alm/projects/{id}/triage/settings", project).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
        permissions.denyAction(2, project, AlmAction.EDIT);
        long submitted = issue(project, 2, "{\"title\":\"외부 요청\",\"description\":\"\"}");
        mvc.perform(get("/api/alm/issues/{id}/comments", submitted).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].body").value("접수했습니다"));
        mvc.perform(get("/api/alm/projects/{id}/triage", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));

        jdbc.update("update automation_rule set next_run_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), scheduled);
        assertThat(scheduler.runDue(Instant.now())).isEqualTo(1);
        assertThat(scheduler.runDue(Instant.now())).isZero();
        mvc.perform(get("/api/alm/issues/{id}", submitted).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.flagged").value(true));

        mvc.perform(post("/api/alm/issues/{id}/triage/decline", submitted).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("WONT_DO"));
        mvc.perform(get("/api/alm/projects/{id}/automation/runs", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private long project(String key) throws Exception {
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long issue(long project, String body) throws Exception {
        return issue(project, 1, body);
    }

    private long issue(long project, long user, String body) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/issues", project).with(asUser(user, "U" + user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static long id(String body) throws Exception {
        return JSON.readTree(body).get("id").asLong();
    }
}
