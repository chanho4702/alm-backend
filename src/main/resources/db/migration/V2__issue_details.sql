ALTER TABLE issue
    ADD COLUMN parent_id BIGINT REFERENCES issue(id) ON DELETE SET NULL,
    ADD COLUMN due_date DATE,
    ADD COLUMN estimate_hours NUMERIC(10, 2),
    ADD COLUMN sort_order BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_issue_estimate_hours_positive
        CHECK (estimate_hours IS NULL OR estimate_hours > 0);

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) AS position
    FROM issue
)
UPDATE issue
SET sort_order = ranked.position
FROM ranked
WHERE issue.id = ranked.id;

ALTER TABLE issue
    ADD CONSTRAINT ck_issue_sort_order_positive CHECK (sort_order > 0);

CREATE INDEX idx_issue_parent ON issue(parent_id);
CREATE INDEX idx_issue_project_sort ON issue(project_id, sort_order, issue_key);

CREATE TABLE issue_label (
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    label_order INTEGER NOT NULL,
    label VARCHAR(80) NOT NULL,
    PRIMARY KEY (issue_id, label_order)
);
