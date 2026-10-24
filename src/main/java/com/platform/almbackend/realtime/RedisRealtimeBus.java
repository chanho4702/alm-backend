package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Redis Streams 팬아웃(운영 기본). 확정 결정 "서비스 간 이벤트는 Redis Streams"와 같은 저장소·같은 도구를 쓰되,
 * <b>서비스 간 이벤트 스트림({@code platform:events:v1}, 아웃박스)과는 따로 둔다</b>:
 * <ul>
 *   <li>아웃박스는 at-least-once 정본 이벤트(검색 색인)라 1초 폴링·재시도를 거친다. 화면 무효화는 지연이 짧아야 하고 잃어도
 *       재연결 재조회로 복구되므로 커밋 뒤 바로 XADD한다(best-effort).</li>
 *   <li>신호 어휘(changedFields·순위·스프린트)가 proto {@code EventEnvelope}에 없다 — 화면 전용 어휘로 공유 proto를 넓히지 않는다.</li>
 *   <li>pub/sub이 아니라 Streams인 이유: 엔트리 id가 노드와 무관한 단일 순서라 SSE {@code id:}로 그대로 쓰고, 재연결 때
 *       {@code Last-Event-ID} 다음부터 XRANGE로 재생할 수 있다(pub/sub은 끊긴 사이 신호가 사라진다). MAXLEN(근사)로 재생 창을 묶는다.</li>
 * </ul>
 * 소비 그룹을 쓰지 않는다 — 모든 노드가 모든 신호를 받아 자기 구독자에게 나눠야 해서다(각 노드가 마지막으로 본 id부터 XREAD BLOCK).
 *
 * <p>발행은 단일 스레드 실행기(대기 {@code queueCapacity})에서 한다 — Redis가 느리거나 죽어도 요청 스레드가 기다리지 않고, 순서가 지켜진다.
 * XADD가 실패한 신호는 이 노드 구독자에게만 id 없이 건넨다(다른 노드는 못 받는다 — 그 화면은 다음 재연결·재조회에서 맞춰진다).
 */
@Slf4j
public class RedisRealtimeBus implements RealtimeBus, DisposableBean {
    static final String FIELD = "s";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String key;
    private final long maxLen;
    private final Duration block;
    private final ThreadPoolExecutor publisher;
    private final Thread reader;
    private volatile Consumer<RealtimeSignal> sink = s -> {};
    private volatile boolean running = true;
    /** 이 노드가 마지막으로 읽은 id — 다음 XREAD 시작점 */
    private volatile String lastSeen;

    public RedisRealtimeBus(StringRedisTemplate redis, ObjectMapper json, String key, long maxLen, Duration block,
                            int queueCapacity) {
        this.redis = redis;
        this.json = json;
        this.key = key;
        this.maxLen = maxLen;
        this.block = block;
        this.publisher = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "alm-realtime-publish");
                    t.setDaemon(true);
                    return t;
                });
        this.reader = new Thread(this::readLoop, "alm-realtime-read");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    @Override
    public void publish(RealtimeSignal signal) {
        try {
            publisher.execute(() -> append(signal));
        } catch (RejectedExecutionException e) {
            log.warn("실시간 신호 발행 대기열이 가득 차 이 노드에만 전달합니다 (project {}, {}.{})",
                    signal.projectId(), signal.event(), signal.kind());
            deliver(signal);
        }
    }

    private void append(RealtimeSignal signal) {
        try {
            byte[] streamKey = key.getBytes(StandardCharsets.UTF_8);
            Map<byte[], byte[]> fields = Map.of(FIELD.getBytes(StandardCharsets.UTF_8),
                    signal.toJson(json).getBytes(StandardCharsets.UTF_8));
            RecordId id = redis.execute((RedisCallback<RecordId>) c -> c.streamCommands().xAdd(
                    MapRecord.create(streamKey, fields), XAddOptions.maxlen(maxLen).approximateTrimming(true)));
            if (id == null) throw new IllegalStateException("XADD가 id를 돌려주지 않았습니다");
            // 이 노드 구독자에게는 읽기 루프가 건넨다(다른 노드와 같은 경로·같은 순서)
        } catch (RuntimeException e) {
            log.warn("실시간 신호를 Redis에 적지 못해 이 노드에만 전달합니다 (project {}, {}.{}): {}",
                    signal.projectId(), signal.event(), signal.kind(), e.toString());
            deliver(signal);
        }
    }

    private void readLoop() {
        long backoffMs = 500;
        while (running) {
            try {
                if (lastSeen == null) {
                    String head = head();
                    if (head == null) throw new IllegalStateException("Redis head 조회 실패");
                    lastSeen = head;
                }
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        StreamReadOptions.empty().count(200).block(block),
                        StreamOffset.create(key, ReadOffset.from(lastSeen)));
                backoffMs = 500;
                if (records == null) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    lastSeen = record.getId().getValue();
                    RealtimeSignal signal = decode(record);
                    if (signal != null) deliver(signal);
                }
            } catch (RuntimeException e) {
                if (!running) return;
                log.warn("실시간 신호 읽기 실패 — {}ms 뒤 다시 시도합니다: {}", backoffMs, e.toString());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 10_000);
            }
        }
    }

    private RealtimeSignal decode(MapRecord<String, Object, Object> record) {
        Object text = record.getValue().get(FIELD);
        if (text == null) return null;
        try {
            return RealtimeSignal.fromJson(json, record.getId().getValue(), text.toString());
        } catch (IllegalArgumentException e) {
            log.warn("실시간 신호 엔트리를 건너뜁니다(모양 오류): {}", record.getId());
            return null;
        }
    }

    private void deliver(RealtimeSignal signal) {
        try {
            sink.accept(signal);
        } catch (RuntimeException e) {
            log.warn("실시간 신호 전달 실패", e);
        }
    }

    @Override
    public void onReceive(Consumer<RealtimeSignal> sink) {
        this.sink = sink;
    }

    @Override
    public String head() {
        try {
            List<MapRecord<String, Object, Object>> last = redis.opsForStream().reverseRange(key, Range.unbounded(), Limit.limit().count(1));
            return last == null || last.isEmpty() ? StreamIds.ZERO : last.get(0).getId().getValue();
        } catch (RuntimeException e) {
            log.debug("실시간 스트림 head 조회 실패: {}", e.toString());
            return null;
        }
    }

    @Override
    public Replay since(String lastId, long projectId, int limit) {
        if (!StreamIds.valid(lastId)) return Replay.gapped();
        try {
            String head = head();
            if (head == null) return Replay.gapped();
            if (StreamIds.compare(lastId, head) > 0) return Replay.gapped();   // 저장소가 초기화됐다
            if (StreamIds.compare(lastId, head) == 0) return new Replay(false, List.of());
            List<MapRecord<String, Object, Object>> first = redis.opsForStream().range(key, Range.unbounded(), Limit.limit().count(1));
            if (first == null || first.isEmpty() || StreamIds.compare(first.get(0).getId().getValue(), lastId) > 0) {
                return Replay.gapped();                                          // 트림으로 밀려났다
            }
            List<MapRecord<String, Object, Object>> rows = redis.opsForStream().range(key,
                    Range.rightUnbounded(Range.Bound.exclusive(lastId)), Limit.limit().count(limit + 1));
            if (rows == null) return Replay.gapped();
            if (rows.size() > limit) return Replay.gapped();                      // 너무 많이 밀렸다 — 통째로 다시 읽는 편이 싸다
            List<RealtimeSignal> out = new ArrayList<>();
            for (MapRecord<String, Object, Object> row : rows) {
                RealtimeSignal signal = decode(row);
                if (signal != null && signal.projectId() == projectId) out.add(signal);
            }
            return new Replay(false, out);
        } catch (RuntimeException e) {
            log.warn("실시간 신호 재생 실패 — 전체 재조회로 안내합니다: {}", e.toString());
            return Replay.gapped();
        }
    }

    /** 테스트 — 읽기 루프가 시작점을 잡았는가 */
    boolean ready() {
        return lastSeen != null;
    }

    @Override
    public void destroy() {
        running = false;
        reader.interrupt();
        publisher.shutdown();
    }
}
