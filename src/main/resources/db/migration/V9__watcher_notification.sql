-- 워처: 이슈 변화를 알림으로 받고 싶은 사용자. 보고자·담당자는 자동으로 들어가고, 누구나 스스로
-- 넣고 뺄 수 있다. 이슈가 지워지면 함께 사라진다.
CREATE TABLE issue_watcher (
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (issue_id, user_id)
);

-- 인앱 알림. 문구는 서버가 만들지 않는다 — 상태 이름·사용자 이름은 프론트(워크플로 레지스트리·
-- 사용자 디렉터리)만 알기 때문에 type + detail(상태 id 등)만 남기고 프론트가 문장을 만든다.
CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    issue_id BIGINT REFERENCES issue(id) ON DELETE CASCADE,
    issue_key VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    detail VARCHAR(200),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notification_user ON notification(user_id, is_read, created_at DESC);
