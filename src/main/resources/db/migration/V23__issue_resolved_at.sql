-- 해결일(resolved_at) — resolution이 null→non-null이 되는 순간 기록, 해제되면 null.
-- 백필: 이미 해결된 이슈는 마지막 갱신 시각을 해결일로 본다(근사치, 그 이상 알 수 없음).
ALTER TABLE issue ADD COLUMN resolved_at TIMESTAMPTZ NULL;
UPDATE issue SET resolved_at = updated_at WHERE resolution IS NOT NULL;
