-- 저장소 폴링 동기화(2026-11-07) — 외부에서 들어오는 연결이 막힌 설치(폐쇄망)에서도 커밋·PR/MR이 이슈에 붙게,
-- ALM이 GitHub(.com·Enterprise Server)·GitLab(.com·self-managed) API를 주기적으로 읽는다. 웹훅이 놓친 이벤트의 안전망도 겸한다.
-- 수집 경로는 웹훅과 같다(V41 dev_commit·issue_commit, issue_web_link kind=PR) — 같은 커밋·PR이 양쪽에서 와도 한 번만 남는다.
--
-- 설치 스위치 ALM_SCM_SYNC(webhook|polling|both, 기본 both)가 웹훅 수신·폴링을 켜고 끈다. 저장소별로는 enabled로 폴링만 끈다.
--
-- repository_scm_sync: 등록 저장소(project_code_repository.url) 하나당 하나 — 웹훅(V44)처럼 URL로 묶어 목록 교체에도 남는다.
--   api_base_url — API 주소(github.com → https://api.github.com, GHES → https://<호스트>/api/v3, GitLab → <오리진>/api/v4).
--                  SSRF 가드: 저장소 URL 호스트(또는 api.<호스트>)만 허용하고, 요청은 이 호스트로만 보낸다(리다이렉트 안 따라감).
--   token_enc    — 읽기 토큰(PAT·GitHub App 설치 토큰 등) AES-256-GCM 암호문(ALM_SETTINGS_ENC_KEY). 원문은 돌려주지 않는다.
--   actor_id     — 수집 주체(토큰을 마지막으로 넣은 관리자). 연결 권한은 웹훅·PAT 수집과 같은 키별 EDIT.
--   *_cursor·*_etag — 증분 커서. 커밋은 커밋 시각 상한(겹침을 두고 다시 읽어도 수집이 멱등), PR/MR은 updated_at 상한.
--   next_run_at·failure_count·rate_limited_until — 주기·백오프·레이트 리밋 대기.
--   lease_owner·lease_until — 다중 인스턴스 중복 실행 방지. 조건부 UPDATE로 임대를 잡은 인스턴스만 돈다(만료되면 다른 쪽이 가져간다).
CREATE TABLE repository_scm_sync (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    repository_url VARCHAR(300) NOT NULL,
    provider VARCHAR(10) NOT NULL,
    api_base_url VARCHAR(300) NOT NULL,
    token_enc VARCHAR(2000) NOT NULL,
    actor_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    token_updated_at TIMESTAMPTZ NOT NULL,
    commits_cursor TIMESTAMPTZ,
    commits_etag VARCHAR(200),
    pulls_cursor TIMESTAMPTZ,
    pulls_etag VARCHAR(200),
    next_run_at TIMESTAMPTZ NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    rate_limited_until TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_outcome VARCHAR(20),
    last_message VARCHAR(300),
    lease_owner VARCHAR(80),
    lease_until TIMESTAMPTZ,
    CONSTRAINT uk_repository_scm_sync_repo UNIQUE (project_id, repository_url),
    CONSTRAINT ck_repository_scm_sync_provider CHECK (provider IN ('GITHUB', 'GITLAB'))
);
-- 주기 실행이 때가 된 행을 찾는다
CREATE INDEX idx_repository_scm_sync_due ON repository_scm_sync (next_run_at) WHERE enabled;

-- PR/MR 상태(생성·병합·닫힘) — 웹훅·폴링이 채운다. 사람이 붙인 PR 링크·옛 행은 NULL(모름)
ALTER TABLE issue_web_link ADD COLUMN state VARCHAR(10);
ALTER TABLE issue_web_link ADD CONSTRAINT ck_issue_web_link_state CHECK (state IS NULL OR state IN ('OPEN', 'MERGED', 'CLOSED'));
