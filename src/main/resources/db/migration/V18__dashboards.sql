-- 대시보드(지라 Dashboards): 사용자가 가젯을 배치하는 화면. 가젯 배치는 JSON 문서로 저장하고
-- 가젯 데이터는 프론트가 기존 API로 계산한다(서버는 배치만 안다).

CREATE TABLE dashboard (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    shared BOOLEAN NOT NULL DEFAULT FALSE,
    gadgets_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_dashboard_owner ON dashboard(owner_id, created_at);
CREATE INDEX idx_dashboard_shared ON dashboard(shared) WHERE shared;

-- 프로젝트 단위 워크로그 조회(가젯·리포트)용
CREATE INDEX idx_worklog_worked_on ON worklog(worked_on);
