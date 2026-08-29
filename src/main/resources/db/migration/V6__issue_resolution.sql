-- 해결(Resolution) — "왜 끝났는가". 완료 카테고리 판정은 프론트 워크플로 스킴 소유라
-- 서버는 값의 유효성만 지키고, 기본값·해제 규칙은 프론트가 적용해 보낸다.
ALTER TABLE issue
    ADD COLUMN resolution VARCHAR(24),
    ADD CONSTRAINT ck_issue_resolution
        CHECK (resolution IS NULL OR resolution IN ('DONE', 'WONT_DO', 'DUPLICATE', 'CANNOT_REPRODUCE'));
