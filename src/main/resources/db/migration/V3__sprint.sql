CREATE TABLE sprint (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    state VARCHAR(16) NOT NULL,
    sprint_number BIGINT NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_sprint_project_number UNIQUE (project_id, sprint_number),
    CONSTRAINT ck_sprint_state CHECK (state IN ('PLANNED', 'ACTIVE', 'DONE')),
    CONSTRAINT ck_sprint_timestamps
        CHECK ((state = 'PLANNED' AND started_at IS NULL AND completed_at IS NULL)
            OR (state = 'ACTIVE' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (state = 'DONE' AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);

-- 한 프로젝트에서 동시에 진행 중인 스프린트는 하나뿐이다. 애플리케이션 검사만으로는
-- 두 요청이 같은 상태를 읽고 둘 다 시작할 수 있으므로 부분 unique 인덱스로 DB가 막는다.
CREATE UNIQUE INDEX uq_sprint_one_active
    ON sprint(project_id) WHERE state = 'ACTIVE';

ALTER TABLE issue
    ADD COLUMN sprint_id BIGINT REFERENCES sprint(id) ON DELETE SET NULL;

-- 백로그/보드 조회는 (프로젝트, 스프린트) 그룹을 정렬 순으로 훑는다.
CREATE INDEX idx_issue_project_sprint_sort ON issue(project_id, sprint_id, sort_order, issue_key);
