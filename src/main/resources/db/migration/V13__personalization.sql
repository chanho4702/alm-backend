-- 지라 메뉴 대조(2026-08-30)에서 가져온 것들: 프로젝트 세부(범주·리더·기본 담당자·아이콘·URL),
-- 프로젝트 바로 가기, 개인 설정(알림·자동 관찰·시작 화면), 전역 공지 배너.

ALTER TABLE project ADD COLUMN category VARCHAR(60) NOT NULL DEFAULT '';
ALTER TABLE project ADD COLUMN lead_id BIGINT;
-- unassigned | lead : 담당자 없이 만든 이슈의 기본 담당자
ALTER TABLE project ADD COLUMN default_assignee VARCHAR(16) NOT NULL DEFAULT 'unassigned';
ALTER TABLE project ADD COLUMN icon VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE project ADD COLUMN color VARCHAR(20) NOT NULL DEFAULT '';
ALTER TABLE project ADD COLUMN url VARCHAR(500) NOT NULL DEFAULT '';

CREATE TABLE project_shortcut (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_project_shortcut_project ON project_shortcut(project_id, sort_order, id);

-- 개인 설정: JSON 문서 한 덩이(알림 이벤트별 on/off, 자동 관찰, 시작 화면)
CREATE TABLE user_preference (
    user_id BIGINT PRIMARY KEY,
    body TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 전역 설정: key → JSON 문서 (지금은 banner 하나)
CREATE TABLE system_setting (
    setting_key VARCHAR(60) PRIMARY KEY,
    body TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
