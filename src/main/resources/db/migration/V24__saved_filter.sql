-- 저장 필터(스마트 검색·AQL) 서버 보관 — 소유자 본인만 조회·수정한다.
CREATE TABLE saved_filter (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    kind VARCHAR(8) NOT NULL,           -- smart | aql
    query VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, name)
);
CREATE INDEX idx_saved_filter_owner ON saved_filter (owner_id);
