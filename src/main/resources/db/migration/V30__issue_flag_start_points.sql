-- 웨이브 D2·D3(2026-10-24) — 플래그(막힘)·시작일·스토리 포인트.
-- 세 값 모두 기존 행에는 "없음"이다(플래그 꺼짐, 시작일·포인트 비어 있음) — 백필하지 않는다.
--
-- 플래그는 보드에서 바로 켜고 끄는 값이라 편집 폼의 version을 올리지 않는다(보드 이동과 같은 규칙).
-- 그래서 PUT 본문에 실리지 않고 전용 엔드포인트(PUT /issues/{id}/flag)로만 바뀐다 — 오래된 폼 저장이 플래그를 되돌리지 않는다.
ALTER TABLE issue ADD COLUMN flagged BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE issue ADD COLUMN start_date DATE;
-- 포인트는 0.5 같은 반 점수까지(지라·피보나치 관례). 상한은 서비스가 9999로 막고, 여기서는 음수만 막는다
ALTER TABLE issue ADD COLUMN story_points NUMERIC(6, 1);
ALTER TABLE issue ADD CONSTRAINT ck_issue_story_points_nonneg CHECK (story_points IS NULL OR story_points >= 0);
-- 시작일이 마감일보다 늦을 수 없다 — 서비스가 400으로 먼저 막고(문구), DB가 뒤를 받친다
ALTER TABLE issue ADD CONSTRAINT ck_issue_start_not_after_due
    CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date);

-- 막힌 이슈는 소수다 — 보드·AQL(flagged = true)이 프로젝트 안에서 찾는 부분 인덱스
CREATE INDEX idx_issue_flagged ON issue (project_id) WHERE flagged;
