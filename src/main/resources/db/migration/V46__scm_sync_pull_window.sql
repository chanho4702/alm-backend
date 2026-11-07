-- 저장소 폴링 PR/MR 창 이어 읽기(2026-11-07, Codex P1) — 한 번에 읽는 PR/MR 상한(max-pull-requests)을 넘으면 창을 끝까지 처리할 때까지
-- 커서·ETag를 움직이지 않는다. 그동안 "이미 처리한 것보다 새 것"을 건너뛰고 더 오래된 쪽을 이어 읽는다.
--   pulls_window_until — 이번 창에서 처리한 가장 오래된 updated_at. 다음 실행은 이보다 새 항목을 건너뛴다(null = 창 없음)
--   pulls_window_max   — 창을 연 실행이 본 가장 새 updated_at. 창을 다 처리하면 pulls_cursor가 여기로 간다
ALTER TABLE repository_scm_sync ADD COLUMN pulls_window_until TIMESTAMPTZ;
ALTER TABLE repository_scm_sync ADD COLUMN pulls_window_max TIMESTAMPTZ;
