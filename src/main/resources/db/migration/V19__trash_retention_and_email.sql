-- 휴지통 자동 비우기(보존 기간)와 이메일 알림 채널.
--
-- 휴지통 쪽은 스키마 변경이 없다 — 보존 기간은 설정값(platform.alm.trash.retention-days)이고
-- 판단 기준은 이미 있는 project.deleted_at이다. 지난 것만 빨리 찾도록 부분 인덱스만 더한다.
CREATE INDEX idx_project_trashed ON project(deleted_at) WHERE deleted_at IS NOT NULL;

-- 이메일 알림(선택 옵션) — 개인 설정의 스위치와 주소 스냅샷.
-- 기본 false: 메일 서버가 없는 설치에서 스위치만 켜져 있고 아무것도 오지 않는 것이 최악이다.
ALTER TABLE user_preference ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT false;
-- 주소는 org 디렉터리가 아니라 요청 토큰(email 클레임)에서 온다 — 발송 시점(다른 사용자의 트랜잭션)에는
-- 수신자의 토큰이 없으므로, 수신자가 마지막으로 다녀갔을 때 본 주소를 쓴다(wiki-backend와 같은 방식).
ALTER TABLE user_preference ADD COLUMN email VARCHAR(320);
