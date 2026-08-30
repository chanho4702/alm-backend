-- 이슈 링크 타입 관리(지라 업무 항목 연결): 고정 2종(blocks/relates) → 전역 레지스트리.
-- outward/inward는 링크 양 끝에서 보이는 문구("차단함"/"차단됨"). 둘이 같으면 대칭(양방향) 링크다.

CREATE TABLE link_type_def (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    outward VARCHAR(80) NOT NULL,
    inward VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO link_type_def (id, name, outward, inward, sort_order, built_in) VALUES
    ('blocks', '차단', '차단함', '차단됨', 1, TRUE),
    ('relates', '관련', '관련됨', '관련됨', 2, TRUE),
    ('duplicates', '중복', '중복함', '중복됨', 3, TRUE),
    ('causes', '원인', '원인임', '결과임', 4, TRUE),
    ('clones', '복제', '복제함', '복제됨', 5, TRUE);

ALTER TABLE issue_link ALTER COLUMN link_type TYPE VARCHAR(40);
CREATE INDEX idx_issue_link_type ON issue_link(link_type);
