-- 저장소 폴링 PR/MR 커서를 키셋으로(2026-11-07, Codex 재지적) — V46의 "창 + 1ms 빼기"는 쪽 상한 너머로 못 가고 같은 시각 PR을
-- 잃었다. 이제 PR/MR을 updated_at 오름차순으로 읽고 (updated_at, 번호) 튜플을 커서로 둔다 — 오래된 것부터 차례로 따라잡고,
-- 같은 시각이어도 번호로 이어 간다.
--   pulls_cursor(기존) + pulls_cursor_number — 마지막으로 처리한 (updated_at, PR 번호·MR iid)
--   pulls_page — GitHub 이어 읽기 쪽 번호(목록에 시각 필터가 없어 쪽으로 따라간다). GitLab은 updated_after로 거르니 쓰지 않는다
--   pulls_etag(기존) — 다 따라잡았을 때만 둔다: "가장 최근 갱신 PR 1건" 탐침의 ETag(304 = 그 뒤 갱신 없음)
-- V46의 창 열은 더 쓰지 않는다(아직 배포 전 — 임시 상태라 지워도 잃는 것이 없다).
ALTER TABLE repository_scm_sync DROP COLUMN pulls_window_until;
ALTER TABLE repository_scm_sync DROP COLUMN pulls_window_max;
ALTER TABLE repository_scm_sync ADD COLUMN pulls_cursor_number BIGINT;
ALTER TABLE repository_scm_sync ADD COLUMN pulls_page INTEGER;
