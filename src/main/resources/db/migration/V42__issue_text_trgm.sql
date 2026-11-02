-- 텍스트 포함 검색 인덱스(2026-11-01) — 빠른 검색(text)·AQL ~ 는 lower(col) LIKE '%…%'라 B-tree를 못 탄다.
-- pg_trgm GIN 인덱스면 앞이 %인 LIKE도 인덱스로 거른다(3글자 이상 검색어부터 효과).
--
-- 권한: pg_trgm은 PG13+에서 "trusted" 확장이라 DB에 CREATE 권한이 있는 롤(DB 소유자)이면 superuser가 아니어도 만든다.
--   기본 compose는 superuser(POSTGRES_USER)로 붙고, 분리 DB 오버레이는 DB 소유자 앱 롤이라 둘 다 된다.
--   외부 DB처럼 권한이 없거나 확장 파일이 없으면 **실패하지 않고 인덱스를 건너뛴다**(검색은 지금처럼 순차 스캔으로 맞게 동작).
--   그 경우 DBA가 `CREATE EXTENSION pg_trgm;` 뒤 아래 CREATE INDEX 세 줄을 손으로 돌리면 된다.
-- 식(lower(col))은 Hibernate가 내는 lower(col) like ? 와 같아야 인덱스를 탄다 — IssueSearchService·AqlSpecification.
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
    EXCEPTION
        WHEN insufficient_privilege OR undefined_file OR feature_not_supported THEN
            RAISE NOTICE 'pg_trgm 확장을 만들 수 없어 텍스트 검색 인덱스를 건너뜁니다: %', SQLERRM;
    END;
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_issue_title_trgm ON issue USING gin (lower(title) gin_trgm_ops)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_issue_description_trgm ON issue USING gin (lower(description) gin_trgm_ops)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_issue_key_trgm ON issue USING gin (lower(issue_key) gin_trgm_ops)';
    END IF;
END
$$;
