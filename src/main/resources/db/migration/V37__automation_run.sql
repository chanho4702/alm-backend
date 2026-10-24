-- 자동화 실행 이력(2026-10-24) — 성공·실패·차단(루프·실행 상한)을 규칙마다 남긴다. 조건에 안 맞아 건너뛴 것은 남기지 않는다(소음).
-- issue_id에는 FK를 걸지 않는다 — 이슈가 지워져도 "그 규칙이 무엇을 했는가"는 남아야 한다. 키는 그때 값을 적어 둔다.
-- 보존: 30일(서비스가 예약 폴러에서 지운다).
CREATE TABLE automation_run (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL REFERENCES automation_rule(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    issue_id BIGINT,
    issue_key VARCHAR(40),
    trigger_type VARCHAR(24) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    depth INTEGER NOT NULL,
    actions_run INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(1000),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_automation_run_outcome CHECK (outcome IN ('SUCCESS', 'FAILED', 'LOOP_BLOCKED', 'RATE_LIMITED'))
);

-- 규칙 화면의 최근 실행, 분당 실행 상한 세기
CREATE INDEX idx_automation_run_rule ON automation_run (rule_id, started_at DESC);
-- 프로젝트 감사 로그(최근 실행)·보존 정리
CREATE INDEX idx_automation_run_project ON automation_run (project_id, started_at DESC);
CREATE INDEX idx_automation_run_started ON automation_run (started_at);
