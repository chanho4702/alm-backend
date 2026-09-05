-- 아바타를 org-service로 이관(2026-09-05). 프로필 사진은 ALM만의 것이 아니라 플랫폼 공통이며,
-- 사용자 디렉터리(GET /api/org/members)가 이미 org-service에 있다. 여기 두면 위키·보드가
-- 같은 얼굴을 보려고 ALM에 의존해야 한다. 정본은 org-service의 member_profile(V7)이다.
--
-- 기존 오브젝트(alm-attachments 버킷의 avatars/...)는 옮기지 않는다 — 개발 단계라 재업로드가 싸다.
-- 남은 바이트는 버킷에서 손으로 지운다.
ALTER TABLE user_preference DROP COLUMN avatar_key;
ALTER TABLE user_preference DROP COLUMN avatar_updated_at;
