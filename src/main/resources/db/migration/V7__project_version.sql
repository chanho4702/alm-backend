-- 버전(릴리스). 프로젝트 안에서 이름이 유일하고, 이슈는 수정 버전(fix version) 하나를 가리킨다.
-- 완료 판정은 프론트 워크플로 스킴 소유라 릴리스 시 "미완료 이슈 이관"은 스프린트 완료처럼
-- 요청이 완료 상태 목록(doneStatuses)을 알려준다.
CREATE TABLE project_version (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    start_date DATE,
    release_date DATE,
    status VARCHAR(16) NOT NULL,
    released_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_project_version_name UNIQUE (project_id, name),
    CONSTRAINT ck_project_version_status CHECK (status IN ('UNRELEASED', 'RELEASED', 'ARCHIVED')),
    CONSTRAINT ck_project_version_dates
        CHECK (start_date IS NULL OR release_date IS NULL OR start_date <= release_date)
);

-- 버전이 지워지면 이슈의 수정 버전은 비워진다(이슈는 남는다).
ALTER TABLE issue
    ADD COLUMN fix_version_id BIGINT REFERENCES project_version(id) ON DELETE SET NULL;

CREATE INDEX idx_issue_fix_version ON issue(fix_version_id);
CREATE INDEX idx_project_version_project ON project_version(project_id, created_at);
