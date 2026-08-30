-- 설정 서버 저장: 상태 카테고리·상태·이슈 타입 레지스트리, 워크플로 스킴, 프로젝트 설정.
-- 지금까지 프론트 localStorage에만 있던 설정 모델을 그대로 옮긴다(id는 문자열 — 기본값은 todo/task 같은
-- 고정 id, 사용자 정의는 cat-*/st-*/it-*/scheme-* 접두).

-- 이슈 타입은 enum이 아니라 레지스트리 id
UPDATE issue SET issue_type = lower(issue_type);
ALTER TABLE issue ALTER COLUMN issue_type TYPE VARCHAR(40);

CREATE TABLE status_category (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL,
    color VARCHAR(16) NOT NULL,
    sort_order INT NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO status_category (id, name, kind, color, sort_order, built_in) VALUES
    ('todo', '할 일', 'new', 'neutral', 1, TRUE),
    ('inprogress', '진행 중', 'active', 'info', 2, TRUE),
    ('done', '완료', 'complete', 'success', 3, TRUE);

CREATE TABLE status_def (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    category_id VARCHAR(40) NOT NULL REFERENCES status_category(id),
    description VARCHAR(300) NOT NULL DEFAULT ''
);
INSERT INTO status_def (id, name, category_id) VALUES
    ('todo', '할 일', 'todo'),
    ('inprogress', '진행 중', 'inprogress'),
    ('done', '완료', 'done');

CREATE TABLE issue_type_def (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    icon VARCHAR(40) NOT NULL,
    color VARCHAR(16) NOT NULL,
    level VARCHAR(16) NOT NULL,
    description VARCHAR(300) NOT NULL DEFAULT '',
    sort_order INT NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO issue_type_def (id, name, icon, color, level, sort_order, built_in) VALUES
    ('task', '작업', 'check-square', 'info', 'standard', 1, TRUE),
    ('story', '스토리', 'bookmark', 'success', 'standard', 2, TRUE),
    ('bug', '버그', 'bug', 'danger', 'standard', 3, TRUE),
    ('epic', '에픽', 'zap', 'warning', 'epic', 4, TRUE),
    ('subtask', '하위 작업', 'list-tree', 'neutral', 'subtask', 5, TRUE);

-- 스킴 본문은 JSON 문서(상태 참조·전이·캔버스 배치·활성 타입) — 규칙 검증은 서비스가 한다
CREATE TABLE settings_scheme (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    body TEXT NOT NULL
);
INSERT INTO settings_scheme (id, name, is_default, body) VALUES (
    'scheme-default', '기본 스킴', TRUE,
    '{"statuses":[{"id":"todo","name":"할 일","category":"todo","order":1},{"id":"inprogress","name":"진행 중","category":"inprogress","order":2},{"id":"done","name":"완료","category":"done","order":3}],"transitions":[],"layout":{},"enabledTypes":["task","story","bug","epic","subtask"]}'
);

CREATE TABLE project_settings (
    project_id BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    scheme_id VARCHAR(40) NOT NULL REFERENCES settings_scheme(id),
    custom_body TEXT
);
INSERT INTO project_settings (project_id, scheme_id) SELECT id, 'scheme-default' FROM project;
