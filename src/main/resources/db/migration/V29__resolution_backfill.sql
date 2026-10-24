-- 해결(resolution) 백필 — 2026-10-24 A1. 지금까지 서버에는 "완료 의미 상태에 들어가면 해결 = 완료" 규칙이 없어,
-- 보드 이동 등으로 완료시킨 이슈의 해결·해결일이 비어 있었다(프론트 목업에만 규칙이 있었다). 이제 서버가 규칙의
-- 정본이 되었으므로, 이미 완료 의미 상태에 있는데 해결이 빈 이슈를 "완료(DONE)"로 채운다(2026-10-24 사용자 승인).
--
-- 완료 의미 판정: 상태 레지스트리(status_def) → 카테고리(status_category.kind = 'complete'). 상태 id는 전역
-- 레지스트리라 프로젝트 본문을 펼치지 않아도 된다(SchemeQueries.enrich가 같은 레지스트리로 kind를 정한다).
-- 레지스트리에 없는 옛 id는 SchemeService.isComplete와 같이 'done'만 완료로 본다.
--
-- 해결일: 현재 상태로 들어온 마지막 상태 변경 시각(issue_change_log STATUS, to_value = 현재 상태). 이력이 없으면
-- (이력 도입 전에 완료됐거나 완료 상태로 생성된 이슈) 마지막 수정 시각으로 근사한다(V23과 같은 근사).
--
-- 보관된 이슈도 포함한다(보관은 되돌릴 수 있고, 되돌렸을 때 같은 규칙이어야 한다).
-- 반대 방향(완료가 아닌데 해결이 남은 이슈)은 건드리지 않는다 — 승인 범위 밖이다. 그런 이슈는 다음 상태
-- 변경 때 서버 규칙이 비운다.
UPDATE issue i
SET resolution = 'DONE',
    resolved_at = COALESCE(
        (SELECT MAX(l.changed_at)
         FROM issue_change_log l
         WHERE l.issue_id = i.id AND l.field = 'STATUS' AND l.to_value = i.status),
        i.updated_at)
WHERE i.resolution IS NULL
  AND (
      EXISTS (SELECT 1
              FROM status_def d
              JOIN status_category c ON c.id = d.category_id
              WHERE d.id = i.status AND c.kind = 'complete')
      OR (i.status = 'done' AND NOT EXISTS (SELECT 1 FROM status_def d WHERE d.id = i.status))
  );
