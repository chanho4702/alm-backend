-- 컴포넌트(지라 Components): 프로젝트 하위 구성 단위. 이슈는 여러 컴포넌트를 가질 수 있고,
-- 컴포넌트의 기본 담당자 규칙(project|lead|unassigned)이 프로젝트 규칙보다 우선한다.

CREATE TABLE component (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    lead_id BIGINT,
    default_assignee VARCHAR(16) NOT NULL DEFAULT 'project',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_component_project_name UNIQUE (project_id, name)
);
CREATE INDEX idx_component_project ON component(project_id, name);

CREATE TABLE issue_component (
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    component_id BIGINT NOT NULL REFERENCES component(id) ON DELETE CASCADE,
    component_order INT NOT NULL,
    PRIMARY KEY (issue_id, component_order)
);
CREATE INDEX idx_issue_component_component ON issue_component(component_id);
