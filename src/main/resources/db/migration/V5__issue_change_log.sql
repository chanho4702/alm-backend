-- 이슈 변경 이력. 번다운·스프린트 리포트가 "언제 완료됐고 스코프가 어떻게 바뀌었나"를
-- 브라우저 저장소가 아니라 서버 기록으로 재현하기 위한 정본이다. 소급 생성이 불가능하므로
-- 리포트 기능보다 먼저 쌓기 시작한다.
--
-- 필드는 지금 STATUS·SPRINT 둘만 쓴다. 일반 컬럼 구조(field/from_value/to_value)라서 필드를
-- 추가할 때 컬럼은 그대로고, 아래 CHECK 제약에 값을 더하는 마이그레이션 한 줄만 필요하다
-- (제약을 지우지 않는다 — 코드 오타가 조용히 저장되는 걸 DB가 막아준다).
-- 모든 필드 변경을 남기는 감사 로그는 별개 과제다 — roadmap 2026-08-28 §10-4.
CREATE TABLE issue_change_log (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    -- 변경 시점의 소속 스프린트(SPRINT 변경이면 옮겨간 쪽). 스프린트 단위 리포트가 한 번에 거른다.
    sprint_id BIGINT,
    field VARCHAR(16) NOT NULL,
    from_value VARCHAR(80),
    to_value VARCHAR(80),
    actor_id BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_issue_change_log_field CHECK (field IN ('STATUS', 'SPRINT'))
);

CREATE INDEX idx_issue_change_log_project ON issue_change_log(project_id, changed_at, id);
CREATE INDEX idx_issue_change_log_sprint ON issue_change_log(project_id, sprint_id, changed_at, id);
CREATE INDEX idx_issue_change_log_issue ON issue_change_log(issue_id, changed_at, id);
