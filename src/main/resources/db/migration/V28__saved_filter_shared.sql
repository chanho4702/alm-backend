-- F-12 저장 필터 공유(2026-10-18): 대시보드 shared와 같은 방식 — 공유 범위는 조직 전체(사용자 결정).
-- 공유 필터는 누구나 열어 실행하지만 결과는 실행하는 사람의 프로젝트 권한으로 걸러진다(검색·AQL이 범위를 좁힌다).
-- 수정·삭제는 소유자만. 기존 필터는 전부 비공개로 남는다.
ALTER TABLE saved_filter ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_saved_filter_shared ON saved_filter (shared) WHERE shared;
