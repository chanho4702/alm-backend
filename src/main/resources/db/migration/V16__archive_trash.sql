-- 보관(archive)·휴지통(trash) — 지라의 "보관된 업무 항목"과 프로젝트 보관/휴지통.
-- 보관된 이슈와 휴지통 프로젝트는 엔티티 @SQLRestriction으로 일반 조회에서 자동으로 빠진다.

ALTER TABLE issue ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE issue ADD COLUMN archived_by BIGINT;
CREATE INDEX idx_issue_archived ON issue(project_id, archived_at) WHERE archived_at IS NOT NULL;

-- archived_at: 읽기 전용 보관(목록에는 남는다) / deleted_at: 휴지통(조회에서 빠진다, 복원·영구 삭제)
ALTER TABLE project ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE project ADD COLUMN deleted_at TIMESTAMPTZ;
