-- 웨이브 D1(2026-10-24) — 저장된 보기: 필터(스마트·AQL) + 표시 옵션(레이아웃·그룹·정렬·표시 속성)을 이름 붙여 저장한다.
--
-- 공유 범위(저장 필터 F-12·V28과 같은 결: 소유자만 고치고, 보는 사람의 권한으로 결과가 걸러진다):
--   personal — 만든 사람만 본다. project_id가 있으면 그 프로젝트 화면의 보기, 없으면 프로젝트 밖(전체 이슈) 보기.
--   project  — 그 프로젝트를 볼 수 있는 사람 모두가 본다. 고치기·지우기는 만든 사람 또는 프로젝트 관리자.
-- 결과(이슈)는 저장하지 않는다 — 여는 사람이 자기 권한으로 질의를 다시 실행한다.
CREATE TABLE saved_view (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    scope VARCHAR(12) NOT NULL,
    name VARCHAR(60) NOT NULL,
    layout VARCHAR(8) NOT NULL,
    filter_kind VARCHAR(8),
    filter_query VARCHAR(4000),
    -- 표시 옵션 JSON(그룹·하위 그룹·정렬·표시 속성·하위 이슈·빈 그룹) — 서비스가 값을 검증해 정규화한 뒤 저장한다
    display_json TEXT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_saved_view_scope CHECK (scope IN ('personal', 'project')),
    CONSTRAINT ck_saved_view_layout CHECK (layout IN ('list', 'board')),
    CONSTRAINT ck_saved_view_project_scope CHECK (scope <> 'project' OR project_id IS NOT NULL),
    CONSTRAINT ck_saved_view_filter_kind CHECK (filter_kind IS NULL OR filter_kind IN ('smart', 'aql')),
    CONSTRAINT ck_saved_view_filter_pair CHECK ((filter_kind IS NULL) = (filter_query IS NULL))
);

-- 내 보기(사이드바) — 소유자로 읽는다
CREATE INDEX idx_saved_view_owner ON saved_view (owner_id);
-- 프로젝트 공유 보기 — 프로젝트 화면이 읽는다
CREATE INDEX idx_saved_view_project ON saved_view (project_id) WHERE scope = 'project';
