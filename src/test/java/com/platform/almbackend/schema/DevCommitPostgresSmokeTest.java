package com.platform.almbackend.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
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
 * V41(커밋 연결)을 실제 PostgreSQL 16 + Flyway(ddl-auto=validate)에서 — ON CONFLICT 멱등, 프로젝트 최근 커밋 키셋(먼 미래 커서·
 * 등록 저장소 IN·이슈 연결 EXISTS), 넓힌 자동화 트리거 CHECK.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
class DevCommitPostgresSmokeTest {
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

    @Test
    void 수집_멱등과_프로젝트_최근_커밋이_PG에서_돈다() throws Exception {
        long projectId = JSON.readTree(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"DEVPG\",\"name\":\"DEVPG\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"description\":\"\",\"type\":\"task\",\"priority\":\"medium\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositories\":[{\"url\":\"https://github.com/o/registered\"}]}"))
                .andExpect(status().isOk());

        String body = "{\"repository\":\"https://github.com/o/registered\",\"commits\":["
                + "{\"sha\":\"" + "1".repeat(40) + "\",\"message\":\"no key\",\"authoredAt\":\"2026-10-20T00:00:00Z\"},"
                + "{\"sha\":\"" + "2".repeat(40) + "\",\"message\":\"DEVPG-1 linked\",\"authoredAt\":\"2026-10-21T00:00:00Z\"}]}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/alm/dev/commits").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stored").value(i == 0 ? 2 : 0))
                    .andExpect(jsonPath("$.duplicates").value(i == 0 ? 0 : 2));
        }
        // 등록 안 된 저장소의 커밋도 이슈로 이어지면 프로젝트 목록에 든다
        mvc.perform(post("/api/alm/dev/commits").with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"https://github.com/o/other\",\"commits\":[{\"sha\":\"" + "3".repeat(40)
                                + "\",\"message\":\"Refs: devpg-1\",\"authoredAt\":\"2026-10-22T00:00:00Z\"}]}"))
                .andExpect(jsonPath("$.linksCreated").value(1));

        JsonNode first = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/commits?limit=2", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).get("repositoryUrl").asText()).isEqualTo("https://github.com/o/other");
        JsonNode second = JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/commits?limit=2&cursor=" + first.get("nextCursor").asText(), projectId)
                        .with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();

        mvc.perform(post("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c\",\"trigger\":{\"type\":\"COMMIT_LINKED\"},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}"))
                .andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("update automation_rule set trigger_type = 'NOPE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
