package com.platform.almbackend.realtime;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 한 JVM 안의 팬아웃(테스트·Redis 없는 단일 노드, {@code platform.alm.realtime.fanout=local}). 신호마다 {@code <ms>-<seq>} id를
 * 매겨 고리 버퍼({@code capacity})에 두고 바로 허브에 넘긴다. 노드를 둘 이상 띄우면 다른 노드의 변경을 못 받는다 — 운영은 Redis다.
 */
@Slf4j
public class LocalRealtimeBus implements RealtimeBus {
    private final int capacity;
    private final Clock clock;
    private final ArrayDeque<RealtimeSignal> buffer = new ArrayDeque<>();
    private volatile Consumer<RealtimeSignal> sink = s -> {};
    private long lastMillis;
    private long seq;

    public LocalRealtimeBus(int capacity, Clock clock) {
        this.capacity = capacity;
        this.clock = clock;
    }

    @Override
    public void publish(RealtimeSignal signal) {
        RealtimeSignal stamped;
        synchronized (this) {
            long now = clock.millis();
            if (now > lastMillis) {
                lastMillis = now;
                seq = 0;
            } else {
                seq++;
            }
            stamped = signal.withId(lastMillis + "-" + seq);
            buffer.addLast(stamped);
            while (buffer.size() > capacity) buffer.removeFirst();
        }
        try {
            sink.accept(stamped);
        } catch (RuntimeException e) {
            log.warn("실시간 신호 전달 실패", e);
        }
    }

    @Override
    public void onReceive(Consumer<RealtimeSignal> sink) {
        this.sink = sink;
    }

    @Override
    public synchronized String head() {
        return buffer.isEmpty() ? (lastMillis == 0 ? StreamIds.ZERO : lastMillis + "-" + seq) : buffer.peekLast().id();
    }

    @Override
    public synchronized Replay since(String lastId, long projectId, int limit) {
        if (!StreamIds.valid(lastId)) return Replay.gapped();
        String head = head();
        if (StreamIds.compare(lastId, head) > 0) return Replay.gapped();
        if (StreamIds.compare(lastId, head) == 0) return new Replay(false, List.of());
        if (buffer.isEmpty() || StreamIds.compare(buffer.peekFirst().id(), lastId) > 0) return Replay.gapped();
        List<RealtimeSignal> out = new ArrayList<>();
        int scanned = 0;
        for (RealtimeSignal s : buffer) {
            if (StreamIds.compare(s.id(), lastId) <= 0) continue;
            if (++scanned > limit) return Replay.gapped();
            if (s.projectId() == projectId) out.add(s);
        }
        return new Replay(false, out);
    }
}
