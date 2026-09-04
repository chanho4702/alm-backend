-- 상태 레지스트리의 아이콘(lucide 키)과 사용자 아바타 키.

-- 상태를 색만으로 구분하지 않기 위해(색각 이상·흑백 인쇄) 상태마다 모양이 있는 아이콘을 고를 수 있게 한다.
-- 값은 프론트 아이콘 맵의 키다. 빈 문자열이면 화면이 카테고리 의미(new/active/complete)의
-- 기본 아이콘으로 폴백하므로, 기존 사용자 정의 상태는 그대로 두고 기본 3종만 채운다.
ALTER TABLE status_def ADD COLUMN icon VARCHAR(40) NOT NULL DEFAULT '';
UPDATE status_def SET icon = 'circle' WHERE id = 'todo' AND icon = '';
UPDATE status_def SET icon = 'loader-circle' WHERE id = 'inprogress' AND icon = '';
UPDATE status_def SET icon = 'circle-check' WHERE id = 'done' AND icon = '';

-- 아바타 바이트는 첨부와 같은 저장소(MinIO/로컬 파일)에 두고 여기에는 키만 남긴다.
-- issue_attachment 행은 만들지 않는다 — 아바타는 이슈에 딸린 파일이 아니고 권한 규칙도 다르다.
ALTER TABLE user_preference ADD COLUMN avatar_key VARCHAR(200);
-- 캐시 무효화용(프론트가 ?v=로 붙인다). 개인 설정 저장 시각(updated_at)과 분리해야
-- 아바타와 무관한 설정 저장이 이미지 URL을 흔들지 않는다.
ALTER TABLE user_preference ADD COLUMN avatar_updated_at TIMESTAMPTZ;
