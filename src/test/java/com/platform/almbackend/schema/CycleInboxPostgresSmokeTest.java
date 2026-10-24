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
import java.time.temporal.ChronoUnit;

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
 * V39(사이클·추정 척도)·V40(알림 인박스)을 실제 PostgreSQL 16 + Flyway(ddl-auto=validate)에서 돈다 — 인박스 키셋 질의의
 * 먼 미래 커서·boolean·enum IN 파라미터, sort_at 백필, CHECK 제약, 사이클 자동 생성이 H2와 다르게 굴지 않는지 본다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
class CycleInboxPostgresSmokeTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private long project(String key) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void 인박스_키셋_필터_미루기가_PG에서_돈다() throws Exception {
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 5; i++) {
            Timestamp at = Timestamp.from(base.plusSeconds(i));
            jdbc.update("insert into notification (user_id, issue_id, issue_key, actor_id, type, is_read, created_at, sort_at) "
                    + "values (42, null, 'PG-1', 1, ?, false, ?, ?)", i == 0 ? "MENTIONED" : "COMMENTED", at, at);
        }
        JsonNode first = JSON.readTree(mvc.perform(get("/api/alm/notifications/inbox?limit=2").with(asUser(42, "N")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("unreadCount").asLong()).isEqualTo(5);
        String cursor = first.get("nextCursor").asText();
        JsonNode second = JSON.readTree(mvc.perform(get("/api/alm/notifications/inbox?limit=2&cursor=" + cursor).with(asUser(42, "N")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.get("items").get(0).get("id").asLong()).isLessThan(first.get("items").get(1).get("id").asLong());

        mvc.perform(get("/api/alm/notifications/inbox?filter=mentions").with(asUser(42, "N")))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/alm/notifications/inbox?filter=unread").with(asUser(42, "N")))
                .andExpect(jsonPath("$.items.length()").value(5));
        long id = first.get("items").get(0).get("id").asLong();
        mvc.perform(post("/api/alm/notifications/{id}/snooze", id).with(asUser(42, "N")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/alm/notifications/inbox?filter=snoozed").with(asUser(42, "N")))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(42, "N")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"action\":\"archive\"}"))
                .andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/api/alm/notifications/inbox?filter=archived").with(asUser(42, "N")))
                .andExpect(jsonPath("$.items.length()").value(1));
        // sort_at은 NOT NULL(V40 백필 뒤)
        assertThatThrownBy(() -> jdbc.update("insert into notification (user_id, issue_key, actor_id, type, is_read, created_at) "
                + "values (42, 'X', 1, 'COMMENTED', false, now())")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 계획_설정_CHECK와_사이클_자동_생성이_PG에서_돈다() throws Exception {
        long projectId = project("PGC");
        mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintAutomation\":{\"autoCreateNext\":true,\"lengthDays\":10,\"defaultCarryOver\":\"next\"},"
                                + "\"estimationScale\":\"tshirt\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        long sprint = JSON.readTree(mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"PG 1\"}")).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"L\",\"description\":\"\",\"details\":{\"sprintId\":" + sprint + "},\"planning\":{\"storyPoints\":5}}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());
        mvc.perform(post("/api/alm/sprints/{id}/complete", sprint).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carryOver.nextSprintAutoCreated").value(true))
                .andExpect(jsonPath("$.carryOver.movedCount").value(1));
        assertThat(jdbc.queryForObject("select name from sprint where project_id = ? and state = 'PLANNED'", String.class, projectId))
                .isEqualTo("PG 2");
        assertThatThrownBy(() -> jdbc.update("update project_planning set estimation_scale = 'powers' where project_id = ?", projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update project_planning set sprint_length_days = 0 where project_id = ?", projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
