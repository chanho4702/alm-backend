package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisRealtimeBus} — 실제 Redis 7(테스트컨테이너)에서 <b>노드 두 개</b>(각자 연결·읽기 루프)를 띄워 노드 간 팬아웃,
 * 단일 순서 id, Last-Event-ID 재생(XRANGE), 트림 뒤 gap, Redis 장애 때 이 노드 전달을 본다.
 */
@Testcontainers
class RedisRealtimeBusTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<AutoCloseable> closers = new ArrayList<>();
    private String key;

    @BeforeEach
    void setUp() {
        key = "alm:realtime:test:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : closers) c.close();
    }

    private RedisRealtimeBus node(String host, int port, long maxLen) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        RedisRealtimeBus bus = new RedisRealtimeBus(template, JSON, key, maxLen, Duration.ofMillis(500), 100);
        closers.add(() -> {
            bus.destroy();
            factory.destroy();
        });
        return bus;
    }

    private RedisRealtimeBus node(long maxLen) {
        return node(redis.getHost(), redis.getMappedPort(6379), maxLen);
    }

    private static RealtimeSignal signal(long projectId, long issueId) {
        return new RealtimeSignal(null, "issue", "updated", projectId, issueId, "P-" + issueId, null, 3L,
                List.of("status"), 1L, Instant.parse("2026-10-24T03:00:00Z"));
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("시간 초과: " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void 한_노드에서_낸_신호를_다른_노드도_같은_id로_받는다() throws Exception {
        RedisRealtimeBus a = node(10_000);
        RedisRealtimeBus b = node(10_000);
        List<RealtimeSignal> gotA = new CopyOnWriteArrayList<>();
        List<RealtimeSignal> gotB = new CopyOnWriteArrayList<>();
        a.onReceive(gotA::add);
        b.onReceive(gotB::add);
        await("두 노드 읽기 시작", () -> a.ready() && b.ready());

        a.publish(signal(7, 1));
        b.publish(signal(7, 2));
        a.publish(signal(8, 3));

        await("노드 B가 셋 다 받음", () -> gotB.size() == 3);
        await("노드 A가 셋 다 받음", () -> gotA.size() == 3);
        // 어느 노드에서 받든 같은 순서·같은 id — Last-Event-ID가 노드를 넘어 통한다
        assertThat(gotA.stream().map(RealtimeSignal::id).toList()).isEqualTo(gotB.stream().map(RealtimeSignal::id).toList());
        assertThat(gotB).allSatisfy(s -> assertThat(StreamIds.valid(s.id())).isTrue());
        RealtimeSignal first = gotB.get(0);
        assertThat(first.projectId()).isEqualTo(7);
        assertThat(first.changedFields()).containsExactly("status");
        assertThat(first.version()).isEqualTo(3L);
        assertThat(b.head()).isEqualTo(gotB.get(2).id());
    }

    @Test
    void 다른_노드에_다시_붙어도_Last_Event_ID_다음부터_그_프로젝트_것만_재생한다() throws Exception {
        RedisRealtimeBus a = node(10_000);
        RedisRealtimeBus b = node(10_000);
        List<RealtimeSignal> got = new CopyOnWriteArrayList<>();
        a.onReceive(got::add);
        await("읽기 시작", a::ready);
        a.publish(signal(7, 1));
        a.publish(signal(9, 2));
        a.publish(signal(7, 3));
        await("셋", () -> got.size() == 3);

        RealtimeBus.Replay replay = b.since(got.get(0).id(), 7, 100);
        assertThat(replay.gap()).isFalse();
        assertThat(replay.signals()).extracting(RealtimeSignal::issueId).containsExactly(3L);
        assertThat(b.since(got.get(2).id(), 7, 100).signals()).isEmpty();
        // 너무 많이 밀렸으면(limit 초과) gap
        assertThat(b.since(got.get(0).id(), 7, 1).gap()).isTrue();
        // 저장소보다 앞선 id(초기화된 Redis) · 형식 아님 → gap
        assertThat(b.since("99999999999999-0", 7, 100).gap()).isTrue();
        assertThat(b.since("abc", 7, 100).gap()).isTrue();
    }

    @Test
    void MAXLEN으로_밀려난_위치에서는_gap이다() throws Exception {
        RedisRealtimeBus a = node(10);
        List<RealtimeSignal> got = new CopyOnWriteArrayList<>();
        a.onReceive(got::add);
        await("읽기 시작", a::ready);
        for (int i = 0; i < 400; i++) a.publish(signal(7, i));
        await("400", () -> got.size() == 400);

        assertThat(a.since(got.get(0).id(), 7, 1000).gap()).isTrue();
        assertThat(a.since(got.get(395).id(), 7, 1000).signals()).hasSize(4);
    }

    @Test
    void Redis에_못_적으면_이_노드_구독자에게만_id_없이_건넨다() throws Exception {
        RedisRealtimeBus dead = node("127.0.0.1", 1, 10_000); // 닫힌 포트
        List<RealtimeSignal> got = new CopyOnWriteArrayList<>();
        dead.onReceive(got::add);

        dead.publish(signal(7, 1));

        await("로컬 전달", () -> got.size() == 1);
        assertThat(got.get(0).id()).isNull();
        assertThat(dead.since("1-0", 7, 10).gap()).isTrue();
    }
}
