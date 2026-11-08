package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이슈 생성 {@code Idempotency-Key}(N10 "생성 버튼 두 번 → 이슈 2개" 재발 방지) — 실제 Redis 7(테스트컨테이너)을 붙인 전체 경로.
 * 마지막 테스트가 Redis를 멈춰 "연결 후 사망" 경로를 본다(그래서 순서를 고정). 처음부터 Redis가 없는 경로는
 * {@code IssueCreateIdempotencyTest}(컴포넌트 단위)가 본다.
 *
 * <p>Spring 컨텍스트는 이 클래스 하나만 새로 띄운다 — 프로퍼티가 다른 클래스를 늘리면 컨텍스트 캐시가 공유되지 않아
 * 테스트 JVM 힙을 잡아먹는다(e84f5e1 CI OOM).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class IssueIdempotencyControllerTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired StringRedisTemplate redisTemplate;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"IDM\",\"name\":\"멱등\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
    }

    private ResultActions create(long userId, String key) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/alm/projects/{id}/issues", projectId)
                .with(asUser(userId, "User" + userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"두 번 누른 이슈\"}");
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    private static long id(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void 같은_키로_두_번_보내면_이슈는_하나이고_두_번째는_200과_재응답_헤더() throws Exception {
        String key = UUID.randomUUID().toString();
        ResultActions first = create(1, key).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        ResultActions second = create(1, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.key").value("IDM-1"));

        assertThat(id(second)).isEqualTo(id(first));
        assertThat(issues.count()).isEqualTo(1);
    }

    @Test
    void 다른_키는_이슈_두_개() throws Exception {
        create(1, UUID.randomUUID().toString()).andExpect(status().isCreated());
        create(1, UUID.randomUUID().toString()).andExpect(status().isCreated());
        assertThat(issues.count()).isEqualTo(2);
    }

    @Test
    void 헤더가_없으면_예전처럼_두_개() throws Exception {
        create(1, null).andExpect(status().isCreated());
        create(1, null).andExpect(status().isCreated());
        assertThat(issues.count()).isEqualTo(2);
    }

    @Test
    void 다른_사용자는_같은_키여도_두_개() throws Exception {
        String key = UUID.randomUUID().toString();
        create(1, key).andExpect(status().isCreated());
        create(2, key).andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(issues.count()).isEqualTo(2);
    }

    @Test
    void 처리_중인_키는_409_error() throws Exception {
        String key = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("alm:idempotency:1:" + key, "pending", Duration.ofSeconds(60));
        create(1, key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("같은 요청이 처리 중입니다"));
        assertThat(issues.count()).isZero();
    }

    @Test
    void 생성이_실패하면_예약을_지워_같은_키로_다시_만들_수_있다() throws Exception {
        String key = UUID.randomUUID().toString();
        permissions.setAllowed(false);
        create(1, key).andExpect(status().is4xxClientError());
        assertThat(redisTemplate.hasKey("alm:idempotency:1:" + key)).isFalse();

        permissions.setAllowed(true);
        create(1, key).andExpect(status().isCreated());
        assertThat(issues.count()).isEqualTo(1);
    }

    @Test
    void 잘못된_키는_400_error() throws Exception {
        create(1, "a".repeat(129)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        assertThat(issues.count()).isZero();
    }

    @Test
    @Order(Integer.MAX_VALUE) // Redis를 멈추므로 맨 마지막
    void 연결_후_Redis가_죽으면_타임아웃_안에_멱등_없이_생성한다(CapturedOutput output) throws Exception {
        // 타임아웃은 application.yml의 spring.data.redis.timeout(기본 5s)을 그대로 쓴다 — 없으면 Lettuce 기본 60초를 기다렸다
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
