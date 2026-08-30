-- 우선순위 스킴(지라 5단계 + 커스텀): 고정 enum(HIGH/MEDIUM/LOW) → 전역 레지스트리 id.
-- 스킴/커스텀 본문에 enabledPriorities·defaultPriority가 붙는다(없으면 전부 활성·medium).

CREATE TABLE priority_def (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    icon VARCHAR(40) NOT NULL,
    color VARCHAR(16) NOT NULL,
    description VARCHAR(300) NOT NULL DEFAULT '',
    sort_order INT NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO priority_def (id, name, icon, color, description, sort_order, built_in) VALUES
    ('highest', '최상', 'chevrons-up', 'danger', '지금 당장 처리해야 한다', 1, TRUE),
    ('high', '높음', 'chevron-up', 'danger', '다른 일보다 먼저 처리한다', 2, TRUE),
    ('medium', '보통', 'equal', 'warning', '순서대로 처리한다', 3, TRUE),
    ('low', '낮음', 'chevron-down', 'info', '여유가 있을 때 처리한다', 4, TRUE),
    ('lowest', '최하', 'chevrons-down', 'neutral', '미뤄도 된다', 5, TRUE);

UPDATE issue SET priority = lower(priority);
ALTER TABLE issue ALTER COLUMN priority TYPE VARCHAR(40);
CREATE INDEX idx_issue_priority ON issue(priority);
