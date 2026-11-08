package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.ServerSocket;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Redis가 죽어 있으면 이슈 생성은 멱등 없이 그대로 성공한다(가용성 우선 — 생성을 Redis에 묶지 않는다). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueIdempotencyRedisDownTest {
    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry r) throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) { closedPort = s.getLocalPort(); }
        int port = closedPort;
        r.add("spring.data.redis.host", () -> "localhost");
        r.add("spring.data.redis.port", () -> port);
        r.add("spring.data.redis.timeout", () -> "500ms");
        r.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
    }

    @Test
    void Redis_불능이면_같은_키여도_둘_다_201로_만든다() throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"RDN\",\"name\":\"레디스 다운\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long projectId = new ObjectMapper().readTree(body).get("id").asLong();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                            .header("Idempotency-Key", "same-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"레디스 없이\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
        assertThat(issues.count()).isEqualTo(2);
    }
}
