-- 이벤트 아웃박스: 도메인 이벤트(EventEnvelope, 본문 없음)를 변경과 같은 트랜잭션에서 한 줄 적고,
-- 커밋 뒤 워커(EventOutboxRelay)가 Redis Streams로 XADD한다. 요청 경로가 Redis를 기다리지 않고,
-- Redis 장애 중에 커밋된 이벤트도 사라지지 않는다(재시도·지수 백오프, 최대 시도 초과분은 last_error와 함께 남김).
-- payload는 스트림에 싣던 EventEnvelope 직렬화 바이트 그대로다 — 스트림 스키마는 바뀌지 않는다.
CREATE TABLE event_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error VARCHAR(1000)
);

-- 워커가 매 주기 읽는 미발행 큐(id 순) — 발행된 행이 쌓여도 스캔이 커지지 않게 부분 인덱스
CREATE INDEX idx_event_outbox_pending ON event_outbox (id) WHERE published_at IS NULL;
-- 보존 기간이 지난 발행분 정리
CREATE INDEX idx_event_outbox_published ON event_outbox (published_at) WHERE published_at IS NOT NULL;

-- 발행 소유권: 한 번에 한 워커만 큐를 id 순으로 비운다. 배치 트랜잭션이 이 한 줄을 SKIP LOCKED로 잡고,
-- 못 잡으면(다른 인스턴스가 비우는 중) 그 주기는 건너뛴다 — 행 단위 SKIP LOCKED만으로는 뒤 배치가
-- 앞 배치보다 먼저 나가 삭제/복원 같은 이벤트 순서가 뒤집힐 수 있다.
CREATE TABLE event_outbox_lock (
    id INTEGER PRIMARY KEY
);
INSERT INTO event_outbox_lock (id) VALUES (1);
