package com.platform.almbackend.issue;

import com.platform.common.error.BadRequestException;
import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IssueCreateIdempotency} — 실제 Redis 7(테스트컨테이너)에서 예약(NX)·완료·재응답·처리 중 409·실패 시 예약 삭제,
 * 그리고 Redis 불능이면 멱등 없이 통과하는지를 본다.
 */
@Testcontainers
class IssueCreateIdempotencyTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private IssueCreateIdempotency idempotency;
    private final AtomicLong nextId = new AtomicLong(100);
    private String key;

    private record Created(long id) {}

    @BeforeEach
    void setUp() {
        factory = factory(redis.getHost(), redis.getMappedPort(6379));
        template = new StringRedisTemplate(factory);
        idempotency = new IssueCreateIdempotency(template);
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    private IssueCreateIdempotency.Result<Created> run(long userId, String k) {
        return idempotency.run(userId, k, () -> new Created(nextId.getAndIncrement()), Created::id, Created::new);
    }

    @Test
    void 같은_사용자_같은_키는_처음_결과를_재응답한다() {
        var first = run(1, key);
        var second = run(1, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.value().id()).isEqualTo(first.value().id());
        assertThat(nextId.get()).isEqualTo(101); // 생성은 한 번
        assertThat(template.opsForValue().get("alm:idempotency:1:" + key)).isEqualTo(String.valueOf(first.value().id()));
        assertThat(template.getExpire("alm:idempotency:1:" + key)).isBetween(86_000L, 86_400L);
    }

    @Test
    void 다른_사용자는_같은_키여도_따로_만든다() {
        var a = run(1, key);
        var b = run(2, key);
        assertThat(b.replayed()).isFalse();
        assertThat(b.value().id()).isNotEqualTo(a.value().id());
    }

    @Test
    void 키가_없으면_매번_만든다() {
        var a = run(1, null);
        var b = run(1, null);
        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isFalse();
        assertThat(b.value().id()).isNotEqualTo(a.value().id());
    }

    @Test
    void 처리_중인_키는_409() {
        template.opsForValue().set("alm:idempotency:1:" + key, "pending", Duration.ofSeconds(60));
        assertThatThrownBy(() -> run(1, key))
                .isInstanceOf(ConflictException.class)
                .hasMessage("같은 요청이 처리 중입니다");
        assertThat(nextId.get()).isEqualTo(100);
    }

    @Test
    void 생성이_실패하면_예약을_지운다() {
        assertThatThrownBy(() -> idempotency.run(1, key, () -> { throw new IllegalStateException("boom"); },
                Created::id, Created::new))
                .isInstanceOf(IllegalStateException.class);
        assertThat(template.hasKey("alm:idempotency:1:" + key)).isFalse();

        // 고친 뒤 같은 키로 다시 보내면 새로 만든다
        var retry = run(1, key);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void 잘못된_키는_400() {
        assertThatThrownBy(() -> run(1, "")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> run(1, "a".repeat(129))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> run(1, "한글키")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> run(1, "has space")).isInstanceOf(BadRequestException.class);
        assertThat(run(1, "a".repeat(128)).replayed()).isFalse();
    }

    @Test
    void Redis가_죽어_있으면_멱등_없이_생성한다() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) { closedPort = s.getLocalPort(); }
        LettuceConnectionFactory dead = factory("localhost", closedPort);
        try {
            IssueCreateIdempotency down = new IssueCreateIdempotency(new StringRedisTemplate(dead));
            var a = down.run(1, key, () -> new Created(nextId.getAndIncrement()), Created::id, Created::new);
            var b = down.run(1, key, () -> new Created(nextId.getAndIncrement()), Created::id, Created::new);
            assertThat(a.replayed()).isFalse();
            assertThat(b.replayed()).isFalse();
            assertThat(b.value().id()).isNotEqualTo(a.value().id());
        } finally {
            dead.destroy();
        }
    }
}
