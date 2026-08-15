CREATE TABLE project (
    id BIGSERIAL PRIMARY KEY,
    project_key VARCHAR(12) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    last_issue_number BIGINT NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE issue (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    issue_number BIGINT NOT NULL,
    issue_key VARCHAR(40) NOT NULL UNIQUE,
    title VARCHAR(300) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    issue_type VARCHAR(20) NOT NULL,
    status VARCHAR(80) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    assignee_id BIGINT,
    reporter_id BIGINT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_issue_project_number UNIQUE (project_id, issue_number)
);

CREATE INDEX idx_issue_project_updated ON issue(project_id, updated_at DESC);
CREATE INDEX idx_issue_project_id_cursor ON issue(project_id, id);

