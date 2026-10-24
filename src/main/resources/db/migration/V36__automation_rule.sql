-- 자동화 규칙(2026-10-24, 지라 Automation 패턴) — 프로젝트 단위 "트리거 → 조건(AQL) → 액션".
--
-- 트리거: ISSUE_CREATED · ISSUE_TRANSITIONED(from/to 상태) · FIELD_CHANGED(필드 목록) · SCHEDULED(cron).
-- 액션: SET_FIELD · ASSIGN · COMMENT · TRANSITION · LABELS · NOTIFY — 전부 사람이 하는 것과 같은 서비스 경로를 탄다
-- (권한·워크플로 전환 규칙·검증·변경 이력·알림·이벤트가 같다). 실행 주체는 actor_id(마지막으로 저장한 프로젝트 관리자)다.
--
-- 안전장치: 규칙이 만든 변경은 기본적으로 다른 규칙을 깨우지 않는다(allow_rule_trigger로만 연다), 연쇄 깊이 상한,
-- 같은 연쇄 안 같은 (규칙, 이슈) 재실행 금지, 규칙당 분당 실행 상한(넘으면 자동 비활성), 연속 실패 상한(넘으면 자동 비활성).
-- 본문(트리거·액션)은 서비스가 닫힌 어휘로 검증한 JSON이다 — 확장해도 마이그레이션이 필요 없다.
CREATE TABLE automation_rule (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_type VARCHAR(24) NOT NULL,
    trigger_json TEXT NOT NULL,
    condition_aql VARCHAR(4000),
    actions_json TEXT NOT NULL,
    allow_rule_trigger BOOLEAN NOT NULL DEFAULT FALSE,
    actor_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    -- 예약 트리거의 다음 실행 시각 — 여러 노드가 같은 시각을 동시에 집지 않게 조건부 UPDATE로 차지한다
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    -- 자동으로 꺼진 이유(실패 누적·실행 상한) — 사람이 다시 켜면 지운다
    disabled_reason VARCHAR(200),
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_automation_rule_trigger CHECK (trigger_type IN ('ISSUE_CREATED', 'ISSUE_TRANSITIONED', 'FIELD_CHANGED', 'SCHEDULED'))
);

-- 이벤트가 오면 (프로젝트, 트리거) 켜진 규칙만 읽는다
CREATE INDEX idx_automation_rule_trigger ON automation_rule (project_id, trigger_type) WHERE enabled;
-- 예약 폴러는 때가 된 켜진 예약 규칙만 본다
CREATE INDEX idx_automation_rule_due ON automation_rule (next_run_at) WHERE enabled AND trigger_type = 'SCHEDULED';
