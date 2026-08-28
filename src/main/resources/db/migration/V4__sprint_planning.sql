-- 스프린트 계획 메타. 목표와 예정 기간은 번다운의 시간축과 스프린트 리포트의 기준이 되므로
-- 실제 시작·완료 시각(started_at/completed_at)과 별도로 보존한다.
ALTER TABLE sprint
    ADD COLUMN goal VARCHAR(255),
    ADD COLUMN planned_start DATE,
    ADD COLUMN planned_end DATE,
    ADD CONSTRAINT ck_sprint_planned_range
        CHECK (planned_start IS NULL OR planned_end IS NULL OR planned_start <= planned_end);
