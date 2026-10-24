package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

/**
 * 프로젝트 실시간 스트림 허브(2026-10-24) — 구독자(SSE)에게 {@link RealtimeBus}가 넘긴 무효화 신호를 나눈다. agent-service 사무실
 * 스트림(OfficeStreamHub, AGP-57)의 패턴을 따른다: 첫 바이트 즉시·주석 줄 heartbeat·사용자당/전체 상한(429)·구독자별 직렬 비동기 전송·
 * 전송 정체 끊기·30분 타임아웃(새 토큰으로 재연결 — 권한도 그때 다시 본다).
 *
 * <p>사무실 스트림과 다른 점:
 * <ul>
 *   <li>신호마다 이슈 id가 달라 "마지막 하나만" 합칠 수 없다 — 구독자별 <b>유한 대기열</b>({@code queueCapacity})에 쌓고, 넘치면
 *       (느린 소비자) 대기열을 비우고 {@code reset} 하나로 바꾼다. 받는 쪽은 reset이면 화면을 통째로 다시 읽는다.</li>
 *   <li>{@code Last-Event-ID} 재연결: 그 다음부터 버스 버퍼에서 그 프로젝트 신호를 재생한다. 이어 줄 수 없으면 reset(gap).
 *       재생하는 동안 들어온 실시간 신호는 붙잡아 두었다가 id로 중복을 걸러 뒤에 붙인다(순서 유지).</li>
 *   <li>권한 재확인: {@code recheckInterval}마다 VIEW를 다시 묻고, 잃었으면 {@code revoked}를 보내고 닫는다(org 불능이면 판정을 미룬다).</li>
 * </ul>
 *
 * <p>SSE 이벤트: {@code ready}(연결·재생 끝, data {projectId, replayed, heartbeatSeconds}) · {@code issue} · {@code sprint}
 * ({@link RealtimeSignal#payload()}) · {@code reset}(data {projectId, reason: gap·overflow}) · {@code revoked}. heartbeat는 주석 줄({@code :hb}).
 */
@Slf4j
public class ProjectStreamHub {

    /** 권한 재확인 결과 */
    public enum Access { ALLOWED, DENIED, UNKNOWN }

    @FunctionalInterface
    public interface ProjectAccess {
        Access check(long userId, long projectId);
    }

    public record Settings(int perUserCap, int globalCap, int queueCapacity, int replayLimit, Duration heartbeat,
                           Duration timeout, Duration writeStall, Duration recheckInterval) {
        public static Settings defaults() {
            return new Settings(5, 500, 100, 1000, Duration.ofSeconds(15), Duration.ofMinutes(30),
                    Duration.ofSeconds(20), Duration.ofMinutes(5));
        }
    }

    private enum Kind { SIGNAL, RESET, READY, REVOKED, HEARTBEAT }

    private record Item(Kind kind, String id, String event, String data) {}

    final class Subscriber {
        final long userId;
        final long projectId;
        final SseEmitter emitter;
        private final ArrayDeque<Item> queue = new ArrayDeque<>();
        private final List<RealtimeSignal> held = new ArrayList<>();
        private boolean replaying = true;
        private boolean closing;
        /** 대기열에 넣은 마지막 신호 id — 재생·실시간이 겹칠 때 중복을 거른다 */
        private String lastQueuedId;
        final AtomicBoolean draining = new AtomicBoolean();
        volatile Instant sendingSince;
        volatile Instant nextRecheck;
        final AtomicBoolean rechecking = new AtomicBoolean();

        Subscriber(long userId, long projectId, SseEmitter emitter) {
            this.userId = userId;
            this.projectId = projectId;
            this.emitter = emitter;
        }

        /** 실시간 신호 — 재생 중이면 붙잡아 둔다 */
        void offerLive(RealtimeSignal signal) {
            synchronized (this) {
                if (closing) return;
                if (replaying) {
                    held.add(signal);
                    return;
                }
                enqueueSignal(signal);
            }
            drain(this);
        }

        /** this 잠금 안에서 */
        private void enqueueSignal(RealtimeSignal signal) {
            if (signal.id() != null) {
                if (lastQueuedId != null && StreamIds.compare(signal.id(), lastQueuedId) <= 0) return;
                lastQueuedId = signal.id();
            }
            enqueue(new Item(Kind.SIGNAL, signal.id(), signal.event(), signal.toJson(json)));
        }

        /** this 잠금 안에서 — 넘치면 대기열을 reset 하나로 바꾼다 */
        private void enqueue(Item item) {
            if (item.kind() == Kind.SIGNAL && queue.size() >= settings.queueCapacity()) {
                queue.clear();
                queue.add(reset(projectId, "overflow"));
                overflows.incrementAndGet();
                return;
            }
            queue.add(item);
        }

        synchronized Item poll() {
            return queue.poll();
        }

        synchronized boolean hasWork() {
            return !queue.isEmpty();
        }
    }

    private final RealtimeBus bus;
    private final ProjectAccess access;
    private final Executor sender;
    private final Clock clock;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Settings settings;
    private final ObjectMapper json;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Subscriber>> byProject = new ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicLong overflows = new java.util.concurrent.atomic.AtomicLong();

    public ProjectStreamHub(RealtimeBus bus, ProjectAccess access, Executor sender, Clock clock,
                            LongFunction<SseEmitter> emitterFactory, Settings settings, ObjectMapper json) {
        this.bus = bus;
        this.access = access;
        this.sender = sender;
        this.clock = clock;
        this.emitterFactory = emitterFactory;
        this.settings = settings;
        this.json = json;
        bus.onReceive(this::dispatch);
    }

    /**
     * 구독 — 권한(VIEW)은 컨트롤러가 이미 확인했다. 상한을 넘으면 {@link RealtimeLimitException}(429).
     *
     * @param lastEventId 재연결이면 마지막으로 받은 신호 id(없으면 null)
     */
    public SseEmitter subscribe(long userId, long projectId, String lastEventId) {
        Subscriber subscriber;
        synchronized (this) {
            if (subscribers.size() >= settings.globalCap()) {
                throw new RealtimeLimitException("실시간 연결이 너무 많습니다 — 잠시 후 다시 시도하세요(화면은 수동 새로 고침으로 계속 쓸 수 있습니다)");
            }
            long mine = subscribers.stream().filter(s -> s.userId == userId).count();
            if (mine >= settings.perUserCap()) {
                throw new RealtimeLimitException("실시간 연결은 사용자당 " + settings.perUserCap() + "개까지입니다 — 다른 탭을 닫고 다시 시도하세요");
            }
            subscriber = new Subscriber(userId, projectId, emitterFactory.apply(settings.timeout().toMillis()));
            subscriber.nextRecheck = clock.instant().plus(settings.recheckInterval());
            subscribers.add(subscriber);
            byProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        }
        SseEmitter emitter = subscriber.emitter;
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(e -> remove(subscriber));
        // 첫 바이트를 바로 — 프록시·클라이언트가 연결 성립을 안다. retry는 EventSource 재연결 간격 힌트(ms)
        sendNow(subscriber, SseEmitter.event().reconnectTime(3000).comment("connected"));

        // 재생 — 등록 뒤에 head를 읽으므로 그 사이 신호는 held에 잡힌다(놓치지 않는다)
        String head = bus.head();
        RealtimeBus.Replay replay = lastEventId == null || lastEventId.isBlank() ? null
                : bus.since(lastEventId.trim(), projectId, settings.replayLimit());
        synchronized (subscriber) {
            int replayed = 0;
            boolean reset = false;
            if (replay != null && replay.gap()) {
                subscriber.enqueue(reset(projectId, "gap"));
                reset = true;
            } else if (replay != null) {
                subscriber.lastQueuedId = lastEventId.trim();
                for (RealtimeSignal s : replay.signals()) subscriber.enqueueSignal(s);
                replayed = replay.signals().size();
            }
            // ready의 id = 지금 head(이 순간까지의 상태는 받는 쪽이 재조회·재생으로 갖고 있다). 그보다 앞선 held는 중복이다
            String readyId = null;
            if (head != null && !StreamIds.ZERO.equals(head)
                    && (subscriber.lastQueuedId == null || StreamIds.compare(head, subscriber.lastQueuedId) > 0)) {
                readyId = head;
                subscriber.lastQueuedId = head;
            }
            subscriber.enqueue(new Item(Kind.READY, readyId, "ready", "{\"projectId\":" + projectId + ",\"replayed\":" + replayed
                    + ",\"reset\":" + reset + ",\"heartbeatSeconds\":" + settings.heartbeat().toSeconds() + "}"));
            subscriber.replaying = false;
            for (RealtimeSignal s : subscriber.held) subscriber.enqueueSignal(s);
            subscriber.held.clear();
        }
        drain(subscriber);
        return emitter;
    }

    /** 버스 → 그 프로젝트 구독자. 막히지 않는다(대기열에 넣기만) */
    void dispatch(RealtimeSignal signal) {
        Set<Subscriber> targets = byProject.get(signal.projectId());
        if (targets == null) return;
        for (Subscriber s : targets) s.offerLive(signal);
    }

    /** 주기 작업(heartbeat 간격) — heartbeat·정체 끊기·권한 재확인 */
    public void tick() {
        Instant now = clock.instant();
        Instant stallBefore = now.minus(settings.writeStall());
        for (Subscriber s : subscribers) {
            Instant since = s.sendingSince;
            if (since != null && since.isBefore(stallBefore)) {
                log.debug("프로젝트 스트림 전송 정체 — 구독 해제: user={} project={}", s.userId, s.projectId);
                remove(s);
                execute(() -> completeQuietly(s, new IOException("프로젝트 스트림 전송 정체")));
                continue;
            }
            if (!now.isBefore(s.nextRecheck) && s.rechecking.compareAndSet(false, true)) {
                s.nextRecheck = now.plus(settings.recheckInterval());
                if (!execute(() -> recheck(s))) s.rechecking.set(false);
            }
            synchronized (s) {
                if (!s.closing && s.queue.isEmpty()) s.queue.add(new Item(Kind.HEARTBEAT, null, null, null));
            }
            drain(s);
        }
    }

    private void recheck(Subscriber s) {
        try {
            Access result;
            try {
                result = access.check(s.userId, s.projectId);
            } catch (RuntimeException e) {
                result = Access.UNKNOWN;
            }
            if (result == Access.DENIED) {
                log.info("프로젝트 스트림 권한 회수 — 닫습니다: user={} project={}", s.userId, s.projectId);
                synchronized (s) {
                    s.queue.clear();
                    s.queue.add(new Item(Kind.REVOKED, null, "revoked", "{\"projectId\":" + s.projectId + "}"));
                    s.closing = true;
                }
                drain(s);
            }
        } finally {
            s.rechecking.set(false);
        }
    }

    private Item reset(long projectId, String reason) {
        return new Item(Kind.RESET, null, "reset", "{\"projectId\":" + projectId + ",\"reason\":\"" + reason + "\"}");
    }

    private void drain(Subscriber s) {
        if (!s.draining.compareAndSet(false, true)) return;
        if (!execute(() -> drainLoop(s))) s.draining.set(false);
    }

    private void drainLoop(Subscriber s) {
        while (true) {
            Item item = s.poll();
            if (item == null) {
                s.draining.set(false);
                // 내려놓는 사이 다른 스레드가 넣었는데 CAS에 져서 물러났을 수 있다
                if (!s.hasWork() || !s.draining.compareAndSet(false, true)) return;
                continue;
            }
            SseEmitter.SseEventBuilder event = switch (item.kind()) {
                case HEARTBEAT -> SseEmitter.event().comment("hb");
                default -> {
                    // 줄 순서가 곧 출력 순서다 — id·event를 data보다 먼저
                    SseEmitter.SseEventBuilder b = SseEmitter.event();
                    if (item.id() != null) b.id(item.id());
                    yield b.name(item.event()).data(item.data());
                }
            };
            if (!sendNow(s, event)) return; // draining은 true로 남긴다 — 끝난 구독
            if (item.kind() == Kind.REVOKED) {
                remove(s);
                completeQuietly(s, null);
                return;
            }
        }
    }

    private boolean execute(Runnable task) {
        try {
            sender.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("프로젝트 스트림 전송 실행기 거부: {}", e.getMessage());
            return false;
        }
    }

    private boolean sendNow(Subscriber s, SseEmitter.SseEventBuilder event) {
        s.sendingSince = clock.instant();
        try {
            s.emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("프로젝트 스트림 전송 실패 — 구독 해제: user={} {}", s.userId, e.getMessage());
            remove(s);
            completeQuietly(s, e);
            return false;
        } finally {
            s.sendingSince = null;
        }
    }

    private static void completeQuietly(Subscriber s, Throwable error) {
        try {
            if (error == null) s.emitter.complete();
            else s.emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            // 이미 끝난 emitter
        }
    }

    private void remove(Subscriber s) {
        synchronized (s) {
            s.closing = true;
        }
        // 프로젝트 색인 정리는 subscribe와 같은 잠금 — 빈 집합을 지우는 순간 새 구독이 그 집합에 들어가 사라지지 않게
        synchronized (this) {
            subscribers.remove(s);
            Set<Subscriber> set = byProject.get(s.projectId);
            if (set != null) {
                set.remove(s);
                if (set.isEmpty()) byProject.remove(s.projectId, set);
            }
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }

    /** 종료 — 모든 구독을 닫는다(클라이언트는 Last-Event-ID로 다른 노드에 다시 붙는다) */
    public void shutdown() {
        for (Subscriber s : List.copyOf(subscribers)) {
            remove(s);
            completeQuietly(s, null);
        }
    }
}
