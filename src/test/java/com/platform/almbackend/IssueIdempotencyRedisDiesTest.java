package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 연결된 뒤 Redis가 죽어도 이슈 생성이 오래 막히지 않는다 — 명령 타임아웃은 application.yml의
 * {@code spring.data.redis.timeout}(기본 5s)을 그대로 쓴다(여기서 덮어쓰지 않는다). 없으면 Lettuce 기본 60초를 기다린 뒤에야 통과했다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class IssueIdempotencyRedisDiesTest {
    // 테스트 중에 멈추므로 @Container(수명 자동 관리)로 두지 않는다
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        redis.start();
    }

    @AfterAll
    static void cleanup() {
        if (redis.isRunning()) redis.stop();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired TestConfig.FakePermissionClient permissions;

    @Test
    void 연결_후_Redis가_죽으면_타임아웃_안에_멱등_없이_생성한다(CapturedOutput output) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"RDD\",\"name\":\"레디스 사망\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long projectId = new ObjectMapper().readTree(body).get("id").asLong();

        // 연결이 살아 있을 때 한 번 — 커넥션을 맺고 멱등 경로가 실제로 Redis를 탔다
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .header("Idempotency-Key", "alive-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"살아 있을 때\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .header("Idempotency-Key", "alive-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"살아 있을 때\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        redis.stop();

        long started = System.nanoTime();
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .header("Idempotency-Key", "dead-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"죽은 뒤\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("Lettuce 기본 60초가 아니라 설정한 5초 타임아웃 안팎").isLessThan(8_000);
        assertThat(issues.count()).isEqualTo(2);
        // 콘솔 인코딩(cp949 등)에 따라 한글이 깨지므로 ASCII 부분으로 본다 — 예약 단계 WARN 한 줄
        assertThat(output.getAll()).containsPattern("WARN.*IssueCreateIdempotency.*Idempotency-Key.*QueryTimeoutException|"
                + "WARN.*IssueCreateIdempotency.*Idempotency-Key.*RedisConnection");
    }
}
