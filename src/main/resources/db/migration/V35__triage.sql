-- 트리아지(2026-10-24, Linear식 대기열) — 일반 워크플로 밖에서 새로 들어온 이슈를 받는다.
--
-- 들어오는 길(정의): ① 트리아지를 켠 프로젝트에서 편집 권한이 없는 열람자가 만든 이슈(EXTERNAL),
-- ② 편집자가 만들 때 triage=true로 보내거나 기존 이슈를 명시적으로 보낸 이슈(MANUAL).
-- 새 상태 분류를 만들지 않는다(kind new/active/complete 고정이 확정 사항) — 대기열은 상태가 아니라 이 열이다.
-- 거절은 해결 WONT_DO, 중복은 "중복" 링크 + 해결 DUPLICATE로 끝낸다.
ALTER TABLE project ADD COLUMN triage_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE issue ADD COLUMN triage_state VARCHAR(12);
ALTER TABLE issue ADD CONSTRAINT ck_issue_triage_state CHECK (triage_state IS NULL OR triage_state IN ('PENDING', 'SNOOZED'));
ALTER TABLE issue ADD COLUMN triage_snoozed_until TIMESTAMPTZ;
ALTER TABLE issue ADD COLUMN triage_source VARCHAR(16);
ALTER TABLE issue ADD CONSTRAINT ck_issue_triage_source CHECK (triage_source IS NULL OR triage_source IN ('EXTERNAL', 'MANUAL'));

-- 대기열 화면은 프로젝트별 들어온 순서 — 대기열 밖 이슈는 인덱스에 넣지 않는다
CREATE INDEX idx_issue_triage ON issue (project_id, created_at) WHERE triage_state IS NOT NULL;
