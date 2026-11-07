-- 저장소 웹훅(2026-11-07) — GitHub(push·pull_request)·GitLab(Push Hook·Merge Request Hook)이 보내는 이벤트를 받아
-- V41 커밋 수집(dev_commit·issue_commit)과 PR 웹 링크(issue_web_link kind=PR)에 그대로 넣는다. 새 커밋 모델은 없다.
--
-- repository_webhook: 프로젝트에 등록한 저장소(project_code_repository.url) 하나당 웹훅 하나.
--   public_id   — 페이로드 URL 경로(/api/alm/webhooks/{github|gitlab}/{public_id})에 들어가는 무작위 값. 비밀이 아니다(인증은 서명).
--                 저장소 등록 행(project_code_repository)은 목록 교체 때 지웠다 다시 만들어 id가 바뀌므로 그 id에 묶지 않는다.
--   secret_enc  — 서명 시크릿. HMAC 검증에 원문이 필요해 해시로 둘 수 없다 → AES-256-GCM(ALM_SETTINGS_ENC_KEY) 암호문만 둔다.
--                 원문은 생성·재발급 응답에 한 번만 나가고 조회 API는 돌려주지 않는다.
--   actor_id    — 수신한 이벤트를 "누구로" 처리할지. 시크릿을 마지막으로 발급한 관리자다 — 연결 권한(대상 프로젝트 EDIT)은
--                 PAT 수집과 똑같이 이 사람으로 판정한다. 그 사람이 권한을 잃으면 키는 NO_PERMISSION으로 건너뛴다(fail-closed).
--   last_*      — 마지막 수신 결과(서명 불일치·실패 포함). 화면의 "최근 수신 상태".
-- repository_webhook_delivery: 처리한 전달(X-GitHub-Delivery 등). (webhook_id, delivery_id) 유일 — 재전송은 다시 처리하지 않는다.
--   처리에 실패한 전달은 남지 않는다(처리와 한 트랜잭션) — 재전송하면 다시 처리된다. 웹훅마다 최근 100건만 둔다.
CREATE TABLE repository_webhook (
    id BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    repository_url VARCHAR(300) NOT NULL,
    provider VARCHAR(10) NOT NULL,
    secret_enc VARCHAR(200) NOT NULL,
    actor_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    secret_rotated_at TIMESTAMPTZ NOT NULL,
    last_delivery_at TIMESTAMPTZ,
    last_event VARCHAR(40),
    last_outcome VARCHAR(20),
    last_message VARCHAR(300),
    CONSTRAINT uk_repository_webhook_public_id UNIQUE (public_id),
    CONSTRAINT uk_repository_webhook_repo UNIQUE (project_id, repository_url),
    CONSTRAINT ck_repository_webhook_provider CHECK (provider IN ('GITHUB', 'GITLAB'))
);

CREATE TABLE repository_webhook_delivery (
    id BIGSERIAL PRIMARY KEY,
    webhook_id BIGINT NOT NULL REFERENCES repository_webhook(id) ON DELETE CASCADE,
    delivery_id VARCHAR(100) NOT NULL,
    event VARCHAR(40) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    message VARCHAR(300),
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_repository_webhook_delivery UNIQUE (webhook_id, delivery_id)
);
-- 화면의 최근 수신 목록·보존 정리(웹훅별 최신 순). 유일 제약 인덱스는 delivery_id 조회를 덮는다
CREATE INDEX idx_repository_webhook_delivery_recent ON repository_webhook_delivery (webhook_id, id DESC);
