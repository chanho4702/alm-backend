-- 개발 기록 — 커밋 연결(2026-10-25). git 커밋을 수집해 메시지 안 이슈 키로 이슈에 잇는다(지라 Development 패널).
--
-- project_code_repository: 프로젝트에 등록한 저장소(프로젝트 설정, ADMIN). url은 서비스가 정규화한 값
--   (scheme://host[:port]/owner/repo — 끝 / · .git 제거, 호스트 소문자, 계정 정보 금지)이라 같은 저장소는 같은 문자열이다.
-- dev_commit: 수집한 커밋 한 건. (repository_url, sha)가 유일 — 같은 커밋을 다시 보내도 행이 늘지 않는다(멱등).
--   저장소 행에 FK를 걸지 않는다 — 등록하지 않은 저장소의 커밋도 이슈 키로 이어질 수 있고, 등록을 지워도 기록은 남는다.
-- issue_commit: 이슈 ↔ 커밋 다대다. (issue_id, commit_id)가 유일 — 연결도 멱등.
CREATE TABLE project_code_repository (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    url VARCHAR(300) NOT NULL,
    default_branch VARCHAR(100) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_project_code_repository_url UNIQUE (project_id, url)
);
-- 수집 때 "이 저장소를 등록한 프로젝트"를 url로 찾는다
CREATE INDEX idx_project_code_repository_url ON project_code_repository (url);

CREATE TABLE dev_commit (
    id BIGSERIAL PRIMARY KEY,
    repository_url VARCHAR(300) NOT NULL,
    repository_name VARCHAR(100) NOT NULL,
    sha VARCHAR(64) NOT NULL,
    short_sha VARCHAR(12) NOT NULL,
    title VARCHAR(300) NOT NULL,
    body_excerpt VARCHAR(2000),
    author_name VARCHAR(200),
    author_email VARCHAR(320),
    authored_at TIMESTAMPTZ NOT NULL,
    branch VARCHAR(200),
    url VARCHAR(500) NOT NULL,
    ingested_by BIGINT NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_dev_commit_repository_sha UNIQUE (repository_url, sha)
);
-- 프로젝트 최근 커밋(등록 저장소 쪽) — 저장소별 최신 순 키셋
CREATE INDEX idx_dev_commit_repository_recent ON dev_commit (repository_url, authored_at DESC, id DESC);

CREATE TABLE issue_commit (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    commit_id BIGINT NOT NULL REFERENCES dev_commit(id) ON DELETE CASCADE,
    linked_by BIGINT NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_issue_commit UNIQUE (issue_id, commit_id)
);
-- 커밋 → 이어진 이슈(프로젝트 최근 커밋의 이슈 열, 프로젝트 소속 판정). issue_id 쪽은 유니크 제약 인덱스가 덮는다
CREATE INDEX idx_issue_commit_commit ON issue_commit (commit_id);

-- 자동화 트리거 COMMIT_LINKED(커밋이 이슈에 새로 이어졌다) — 닫힌 어휘 CHECK를 넓힌다
ALTER TABLE automation_rule DROP CONSTRAINT ck_automation_rule_trigger;
ALTER TABLE automation_rule ADD CONSTRAINT ck_automation_rule_trigger
    CHECK (trigger_type IN ('ISSUE_CREATED', 'ISSUE_TRANSITIONED', 'FIELD_CHANGED', 'SCHEDULED', 'COMMIT_LINKED'));
