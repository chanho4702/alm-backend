-- 이슈 외부 링크(PR·커밋·웹) — 에이전트 git 연결(P2a). 이슈↔이슈 링크(issue_link)와 별개.
-- 같은 issue_id+url이면 서비스가 기존 행을 반환한다(멱등) — 커밋 파서가 재실행돼도 중복이 쌓이지 않는다.
CREATE TABLE issue_web_link (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    url VARCHAR(500) NOT NULL,
    title VARCHAR(200),
    kind VARCHAR(20) NOT NULL,          -- PR | COMMIT | WEB
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_web_link_issue ON issue_web_link (issue_id);
