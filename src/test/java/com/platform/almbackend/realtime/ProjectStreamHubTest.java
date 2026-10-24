package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProjectStreamHub} — 프로젝트 필터·heartbeat·Last-Event-ID 재생(gap·중복)·상한·느린 소비자(대기열 넘침 → reset, 전송 정체 끊기)·
 * 권한 재확인. 전송은 호출 스레드에서 바로(Runnable::run), 시계는 손으로 돌린다.
 */
class ProjectStreamHubTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-24T03:00:00Z");

    private MutableClock clock;
    private LocalRealtimeBus bus;
    private List<CapturingEmitter> emitters;
    private AtomicReference<ProjectStreamHub.Access> access;
    private ProjectStreamHub hub;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        bus = new LocalRealtimeBus(50, clock);
        emitters = new ArrayList<>();
        access = new AtomicReference<>(ProjectStreamHub.Access.ALLOWED);
        hub = newHub(Runnable::run, new ProjectStreamHub.Settings(2, 3, 5, 20, Duration.ofSeconds(15),
                Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(5)));
    }

    private ProjectStreamHub newHub(java.util.concurrent.Executor sender, ProjectStreamHub.Settings settings) {
        return new ProjectStreamHub(bus, (u, p) -> access.get(), sender, clock, timeout -> {
            CapturingEmitter e = new CapturingEmitter(timeout);
            emitters.add(e);
            return e;
        }, settings, JSON);
    }

    private CapturingEmitter open(long userId, long projectId, String lastEventId) {
        hub.subscribe(userId, projectId, lastEventId);
        return emitters.get(emitters.size() - 1);
    }

    private RealtimeSignal issue(long projectId, long issueId, String kind) {
        return new RealtimeSignal(null, "issue", kind, projectId, issueId, "P-" + issueId, null, 2L,
                "updated".equals(kind) ? List.of("assignee") : null, 9L, clock.instant());
    }

    @Test
    void 연결하면_connected_주석과_ready가_가고_그_프로젝트_신호만_id와_함께_받는다() {
        CapturingEmitter p7 = open(1, 7, null);
        CapturingEmitter p8 = open(2, 8, null);

        bus.publish(issue(7, 100, "updated"));
        bus.publish(issue(8, 200, "created"));

        assertThat(p7.sent.get(0)).contains(":connected").contains("retry:3000");
        assertThat(p7.events("ready")).singleElement().asString()
                .contains("\"projectId\":7").contains("\"replayed\":0").contains("\"heartbeatSeconds\":15");
        assertThat(p7.events("issue")).singleElement().asString()
                .startsWith("id:" + T0.toEpochMilli() + "-0\nevent:issue\n")
                .contains("\"type\":\"issue.updated\"").contains("\"issueId\":100").contains("\"version\":2")
                .contains("\"changedFields\":[\"assignee\"]")
                .doesNotContain("title").doesNotContain("description");
        assertThat(p8.events("issue")).singleElement().asString().contains("\"issueId\":200");
    }

    @Test
    void heartbeat는_주석_줄이고_보낼_것이_있으면_생략한다() {
        CapturingEmitter sub = open(1, 7, null);
        int before = sub.sent.size();
        hub.tick();
        assertThat(sub.sent.subList(before, sub.sent.size())).containsExactly(":hb\n\n");
    }

    @Test
    void Last_Event_ID로_다시_붙으면_놓친_그_프로젝트_신호만_순서대로_재생한다() {
        CapturingEmitter first = open(1, 7, null);
        bus.publish(issue(7, 1, "created"));
        String lastSeen = first.ids().get(first.ids().size() - 1);
        first.complete();
        clock.advance(Duration.ofMillis(5));
        bus.publish(issue(7, 2, "created"));
        bus.publish(issue(8, 3, "created"));   // 다른 프로젝트
        bus.publish(issue(7, 4, "deleted"));

        CapturingEmitter again = open(1, 7, lastSeen);

        assertThat(again.events("issue")).hasSize(2);
        assertThat(again.events("issue").get(0)).contains("\"issueId\":2");
        assertThat(again.events("issue").get(1)).contains("\"issueId\":4").contains("issue.deleted");
        assertThat(again.events("ready")).singleElement().asString().contains("\"replayed\":2").contains("\"reset\":false");
        assertThat(again.events("reset")).isEmpty();
    }

    @Test
    void 이어_줄_수_없으면_reset_gap() {
        // 버퍼(50)보다 많이 밀렸다 → 첫 id가 밀려났다
        CapturingEmitter first = open(1, 7, null);
        bus.publish(issue(7, 1, "created"));
        String lastSeen = first.ids().get(0);
        for (int i = 0; i < 60; i++) bus.publish(issue(9, 1000 + i, "created"));

        CapturingEmitter again = open(2, 7, lastSeen);
        assertThat(again.events("reset")).singleElement().asString().contains("\"reason\":\"gap\"");
        assertThat(again.events("ready")).singleElement().asString().contains("\"reset\":true");

        // 형식이 아닌 id·저장소보다 앞선 id도 gap
        assertThat(open(2, 7, "nope").events("reset")).hasSize(1);
    }

    @Test
    void 사용자당_상한과_전체_상한을_넘으면_429_예외() {
        open(1, 7, null);
        open(1, 8, null);
        assertThatThrownBy(() -> hub.subscribe(1, 9, null))
                .isInstanceOf(RealtimeLimitException.class).hasMessageContaining("사용자당 2개");
        open(2, 7, null);
        assertThatThrownBy(() -> hub.subscribe(3, 7, null))
                .isInstanceOf(RealtimeLimitException.class).hasMessageContaining("너무 많습니다");
        // 끊기면 자리를 돌려준다
        emitters.get(0).fireCompletion();
        open(3, 7, null);
        assertThat(hub.subscriberCount()).isEqualTo(3);
    }

    @Test
    void 느린_소비자의_대기열이_넘치면_reset_하나로_바뀌고_다른_구독자는_막히지_않는다() throws Exception {
        ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
        try {
            hub = newHub(sender, new ProjectStreamHub.Settings(5, 10, 5, 20, Duration.ofSeconds(15),
                    Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(5)));
            CapturingEmitter slow = open(1, 7, null);
            CapturingEmitter fast = open(2, 7, null);
            awaitEvents(slow, "ready", 1);
            CountDownLatch release = new CountDownLatch(1);
            slow.block = release;

            bus.publish(issue(7, 1, "created"));
            assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();   // 첫 신호 전송에서 막혔다
            awaitEvents(fast, "issue", 1);
            for (int i = 2; i <= 12; i++) {
                bus.publish(issue(7, i, "created"));
                awaitEvents(fast, "issue", i);                                // 빠른 쪽은 막히지 않고 하나씩 다 받는다
            }
            release.countDown();
            awaitEvents(slow, "reset", 1);
            assertThat(slow.events("reset").get(0)).contains("\"reason\":\"overflow\"");
            assertThat(slow.events("issue").size()).isLessThan(12);
            assertThat(hub.overflows.get()).isPositive();
        } finally {
            sender.shutdownNow();
        }
    }

    @Test
    void 전송이_정체된_구독은_heartbeat_틱이_끊어_자리를_돌려준다() throws Exception {
        ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
        try {
            hub = newHub(sender, new ProjectStreamHub.Settings(5, 10, 5, 20, Duration.ofSeconds(15),
                    Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(5)));
            CapturingEmitter stuck = open(1, 7, null);
            awaitEvents(stuck, "ready", 1);
            stuck.block = new CountDownLatch(1);
            bus.publish(issue(7, 1, "created"));
            assertThat(stuck.entered.await(5, TimeUnit.SECONDS)).isTrue();

            clock.advance(Duration.ofSeconds(21));
            hub.tick();
            assertThat(hub.subscriberCount()).isZero();
        } finally {
            sender.shutdownNow();
        }
    }

    @Test
    void 권한을_잃으면_재확인_주기에_revoked를_보내고_닫는다_org_불능은_보류() {
        CapturingEmitter sub = open(1, 7, null);
        access.set(ProjectStreamHub.Access.UNKNOWN);
        clock.advance(Duration.ofMinutes(5));
        hub.tick();
        assertThat(hub.subscriberCount()).isEqualTo(1);
        assertThat(sub.events("revoked")).isEmpty();

        access.set(ProjectStreamHub.Access.DENIED);
        clock.advance(Duration.ofMinutes(4));
        hub.tick();                                   // 아직 주기 전
        assertThat(sub.events("revoked")).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        hub.tick();
        assertThat(sub.events("revoked")).singleElement().asString().contains("\"projectId\":7");
        assertThat(sub.completed).isTrue();
        assertThat(hub.subscriberCount()).isZero();
    }

    @Test
    void 끊긴_연결로의_전송_실패는_구독을_지운다() {
        CapturingEmitter sub = open(1, 7, null);
        sub.failNext = true;
        bus.publish(issue(7, 1, "created"));
        assertThat(hub.subscriberCount()).isZero();
    }

    private static void awaitEvents(CapturingEmitter emitter, String name, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (emitter.events(name).size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(name + " " + count + "건을 기다리다 시간 초과: " + emitter.sent);
            }
            Thread.sleep(10);
        }
    }

    static final class CapturingEmitter extends SseEmitter {
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean failNext;
        volatile CountDownLatch block;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean completed;
        private Runnable onCompletion = () -> {};

        CapturingEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failNext) {
                failNext = false;
                throw new IOException("broken pipe");
            }
            CountDownLatch gate = block;
            if (gate != null) {
                entered.countDown();
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) throw new IOException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                block = null;
            }
            sent.add(builder.build().stream().map(d -> d.getData().toString()).collect(Collectors.joining()));
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.onCompletion = callback;
            super.onCompletion(callback);
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completed = true;
        }

        void fireCompletion() {
            onCompletion.run();
        }

        List<String> events(String name) {
            return sent.stream().filter(s -> s.contains("event:" + name + "\n")).toList();
        }

        List<String> ids() {
            List<String> out = new ArrayList<>();
            for (String s : sent) {
                if (s.startsWith("id:")) out.add(s.substring(3, s.indexOf('\n')));
            }
            return out;
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
