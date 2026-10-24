-- 예약 규칙 커서(2026-10-24, Codex P1) — 회차당 이슈 상한(schedule-batch)을 넘는 대상을 회차마다 이어서 돈다.
-- 값은 이번 바퀴에서 마지막으로 처리한 이슈 id다(대상은 id 오름차순). NULL이면 다음 회차는 처음부터 — 바퀴 끝에 닿거나
-- 규칙을 다시 저장하면 NULL로 돌아간다. 예전에는 매 회차 page 0만 읽어 COMMENT·NOTIFY처럼 조건을 바꾸지 않는 액션이
-- 첫 배치만 영원히 처리했다.
ALTER TABLE automation_rule ADD COLUMN schedule_cursor BIGINT;
