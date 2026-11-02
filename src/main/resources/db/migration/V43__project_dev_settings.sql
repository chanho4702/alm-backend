-- 프로젝트 개발 설정(2026-11-01) — git 브랜치 이름 접두. 프로젝트당 한 줄, 행이 없으면 접두 없음(기존 동작 그대로).
-- 브랜치 이름은 여전히 프론트가 만든다(README "이슈 키·브랜치 이름 규칙"): `[<접두>/]<키 소문자>-<슬러그>`.
-- 접두 규칙(서비스가 검증): 영숫자·`.`·`_`·`-`·`/`, 50자 이하, `/`로 시작하지 않고 `//`·`..` 없음, 부분이 `.`·`-`로 시작하거나 `.`·`.lock`으로 끝나지 않음.
CREATE TABLE project_dev_settings (
    project_id BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    branch_prefix VARCHAR(50) NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL
);
