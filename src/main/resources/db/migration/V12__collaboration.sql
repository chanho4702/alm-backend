-- 협업 데이터 서버화: 코멘트·워크로그·이슈 링크·보드·활동 기록. 지금까지 프론트 localStorage에만 있던 것을
-- 옮긴다(프론트 목업과 같은 규칙: 본인 코멘트/워크로그만 수정·삭제, 링크 중복 금지, 마지막 보드 삭제 금지).

CREATE TABLE issue_comment (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ
);
CREATE INDEX idx_issue_comment_issue ON issue_comment(issue_id, created_at);

CREATE TABLE worklog (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL,
    hours NUMERIC(8,2) NOT NULL,
    comment VARCHAR(500) NOT NULL DEFAULT '',
    worked_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_worklog_hours CHECK (hours > 0)
);
CREATE INDEX idx_worklog_issue ON worklog(issue_id, worked_on);

-- blocks: source가 target을 차단(방향 있음), relates: 양방향(레코드 1개)
CREATE TABLE issue_link (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    target_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    link_type VARCHAR(16) NOT NULL,
    CONSTRAINT ck_issue_link_self CHECK (source_id <> target_id)
);
CREATE INDEX idx_issue_link_source ON issue_link(source_id);
CREATE INDEX idx_issue_link_target ON issue_link(target_id);

-- 보드 = 보는 방법(필터·컬럼 오버라이드·스윔레인)만 저장하는 뷰. filter/columns는 JSON 문서
CREATE TABLE board (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    board_type VARCHAR(16) NOT NULL,
    filter_json TEXT NOT NULL,
    columns_json TEXT NOT NULL,
    swimlane VARCHAR(16) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_board_project ON board(project_id, created_at);
-- 기존 프로젝트에도 기본 보드 하나
INSERT INTO board (project_id, name, board_type, filter_json, columns_json, swimlane, is_default, created_at)
SELECT id, '메인 보드', 'scrum', '{"assigneeIds":[],"types":[],"labels":[]}', '[]', 'none', TRUE, now() FROM project;

-- 활동 기록: 이슈 상세 "활동" 탭의 원천(상태·담당자·우선순위·… 변경, 링크, 워크로그, 첨부)
CREATE TABLE issue_activity (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL,
    activity_type VARCHAR(24) NOT NULL,
    detail VARCHAR(500) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_issue_activity_issue ON issue_activity(issue_id, occurred_at, id);
