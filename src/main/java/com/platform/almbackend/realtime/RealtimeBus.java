package com.platform.almbackend.realtime;

import java.util.List;
import java.util.function.Consumer;

/**
 * 실시간 신호의 노드 간 팬아웃 + 재연결 재생 버퍼.
 *
 * <ul>
 *   <li>{@link RedisRealtimeBus}(운영 기본) — 전용 Redis Stream {@code alm:realtime:v1}에 XADD, 노드마다 XREAD(소비 그룹 없음 —
 *       모든 노드가 모든 신호를 받는다)로 읽어 자기 구독자에게 나눈다. 엔트리 id가 노드와 무관한 단일 순서라
 *       {@code Last-Event-ID} 재생(XRANGE)이 어느 노드에 다시 붙어도 맞다.</li>
 *   <li>{@link LocalRealtimeBus} — 한 JVM 안에서만(테스트·Redis 없는 단일 노드). 같은 id 형식의 메모리 고리 버퍼.</li>
 * </ul>
 */
public interface RealtimeBus {

    /** 신호를 낸다(id는 버스가 매긴다). 실패해도 던지지 않는다 — 이미 커밋된 변경이다 */
    void publish(RealtimeSignal signal);

    /** 이 노드가 받은 신호를 넘길 곳 — 허브가 한 번 등록한다 */
    void onReceive(Consumer<RealtimeSignal> sink);

    /** 지금 버퍼의 마지막 id(비면 {@link StreamIds#ZERO}). 못 읽으면 null */
    String head();

    /**
     * {@code lastId} 다음부터 그 프로젝트 신호를 최대 {@code limit}개(버퍼 전체에서 훑는 엔트리 기준). 이어 줄 수 없으면 gap —
     * 버퍼에서 이미 밀려났거나(트림), 저장소가 초기화됐거나(lastId가 head보다 뒤), 너무 많이 밀렸거나, id가 형식이 아니면.
     */
    Replay since(String lastId, long projectId, int limit);

    record Replay(boolean gap, List<RealtimeSignal> signals) {
        static Replay gapped() {
            return new Replay(true, List.of());
        }
    }
}
