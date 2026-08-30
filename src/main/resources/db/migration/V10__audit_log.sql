-- 감사 로그: 누가 무엇을 언제. 도메인 이벤트(EventEnvelope)와 같은 트랜잭션에서 한 줄씩 남긴다 —
-- Redis Streams 발행은 사후(비차단)라 유실될 수 있지만 감사 로그는 커밋과 함께 남아야 한다.
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    event_type VARCHAR(48) NOT NULL,
    actor_id BIGINT NOT NULL,
    project_id BIGINT,
    target_key VARCHAR(80),
    summary VARCHAR(300),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_time ON audit_log(occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_project ON audit_log(project_id, occurred_at DESC);
