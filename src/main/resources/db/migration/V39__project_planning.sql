-- 사이클(스프린트 자동화)·추정 척도(2026-10-24, Linear 강점 반영) — 프로젝트당 한 줄.
--
-- 행이 없으면 기본값(자동 생성 끔·14일·쿨다운 0일·이월 백로그·척도 linear)이다 — 기존 프로젝트의 동작을 바꾸지 않는다.
-- 시드 없음: 처음 저장할 때 생긴다.
--
-- 사이클: 스프린트를 완료할 때 다음(예정) 스프린트가 없고 auto_create_next면 다음 스프린트를 만든다
--   (예정 시작일 = 완료일 + cooldown_days, 예정 종료일 = 시작일 + length_days - 1, 서울 날짜).
--   default_carry_over는 완료 요청이 이관 대상을 지정하지 않았을 때 미완료 이슈가 갈 곳(next = 다음 스프린트, backlog).
-- 추정 척도: story_points(NUMERIC(6,1))는 그대로 두고 허용 값만 정한다. T셔츠는 크기 → 포인트 숫자 매핑이라
--   합계·속도는 늘 숫자로 센다. linear는 제한 없음(0~9999, 소수 첫째 자리 — V30 그대로).
--   custom_values·tshirt_points는 서비스가 검증·정규화한 JSON 텍스트다(H2 테스트와 같은 타입을 쓰려고 JSONB가 아니다).
CREATE TABLE project_planning (
    project_id BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    auto_create_next BOOLEAN NOT NULL DEFAULT FALSE,
    sprint_length_days INTEGER NOT NULL DEFAULT 14,
    cooldown_days INTEGER NOT NULL DEFAULT 0,
    default_carry_over VARCHAR(8) NOT NULL DEFAULT 'backlog',
    estimation_scale VARCHAR(12) NOT NULL DEFAULT 'linear',
    custom_values TEXT,
    tshirt_points TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_project_planning_length CHECK (sprint_length_days BETWEEN 1 AND 90),
    CONSTRAINT ck_project_planning_cooldown CHECK (cooldown_days BETWEEN 0 AND 30),
    CONSTRAINT ck_project_planning_carry CHECK (default_carry_over IN ('next', 'backlog')),
    CONSTRAINT ck_project_planning_scale CHECK (estimation_scale IN ('fibonacci', 'tshirt', 'linear', 'custom')),
    CONSTRAINT ck_project_planning_custom CHECK (estimation_scale <> 'custom' OR custom_values IS NOT NULL)
);
