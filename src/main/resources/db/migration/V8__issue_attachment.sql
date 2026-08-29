-- 이슈 첨부 메타데이터. 바이트는 오브젝트 스토리지(운영: MinIO S3 호환, 개발/테스트: 로컬 파일)에
-- storage_key(UUID)로 저장한다 — 원본 파일명은 표시용일 뿐 경로에 쓰지 않는다(경로 조작 차단).
-- 이슈가 지워지면 메타는 cascade로 사라지고 오브젝트는 서비스가 정리한다.
CREATE TABLE issue_attachment (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_bucket VARCHAR(255),
    storage_key VARCHAR(64) NOT NULL UNIQUE,
    checksum_sha256 VARCHAR(64),
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_issue_attachment_size CHECK (size_bytes > 0)
);

CREATE INDEX idx_issue_attachment_issue ON issue_attachment(issue_id, created_at);
