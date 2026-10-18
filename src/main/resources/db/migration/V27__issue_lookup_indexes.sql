-- 2026-10-18 감사 A-13·A-07: 자주 거르는 열의 인덱스.
-- 담당자: 홈 "나에게 배정", 보드·검색 담당자 필터, AQL assignee — 지금까지 전 테이블 스캔이었다.
-- (assignee_id, project_id) 순서: "나에게 배정"(프로젝트 무관)과 "프로젝트 안 담당자"를 함께 탄다.
CREATE INDEX idx_issue_assignee ON issue(assignee_id, project_id);
-- 보고자: AQL reporter, 참여자 후보(findParticipantUserIds)
CREATE INDEX idx_issue_reporter ON issue(reporter_id);
-- 라벨 필터(보드·검색 EXISTS)와 AQL labels — PK (issue_id, label_order)로는 라벨 값으로 찾지 못한다
CREATE INDEX idx_issue_label_label ON issue_label(label);
