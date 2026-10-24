-- 알림 인박스(2026-10-24, Linear 인박스) — 미루기·보관·커서 페이징.
--
-- sort_at: 인박스 정렬 기준. 처음엔 created_at이고, 미루면 미루기 해제 시각이 된다 — 해제 시각이 지나면 그 시각에
--   새로 온 알림처럼 다시 위로 올라온다. 미루는 동안(snoozed_until > now)에는 기본 인박스에서 빠진다.
-- archived_at: 삭제 대신 보관. 보관함 필터로만 보인다(되돌릴 수 있다).
-- 커서는 (sort_at, id) 내림차순 키셋이다.
ALTER TABLE notification ADD COLUMN snoozed_until TIMESTAMPTZ;
ALTER TABLE notification ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE notification ADD COLUMN sort_at TIMESTAMPTZ;
UPDATE notification SET sort_at = created_at WHERE sort_at IS NULL;
ALTER TABLE notification ALTER COLUMN sort_at SET NOT NULL;

-- 인박스(보관 제외) 키셋 페이징. 보관함은 드물게 열고 건수가 작아 user_id 앞부분(idx_notification_user)으로 충분하다
CREATE INDEX idx_notification_inbox ON notification (user_id, sort_at DESC, id DESC) WHERE archived_at IS NULL;
