-- issue_web_link 멱등을 DB가 보장한다(AGP-49) — V22까지는 서비스의 조회-후-삽입뿐이라 동시 요청이면 같은
-- issue_id+url 행이 둘 생길 수 있었다.
--
-- ① 기존 중복 정리: (issue_id, url)마다 가장 먼저 만든 행(최소 id)만 남긴다. 서비스는 기존 행을 그대로
--    돌려주므로 호출자가 처음 받은 id가 최소 id다 — 나중 행을 지워도 이미 나간 id가 사라지지 않는다.
--    url은 서비스가 trim만 하고 대소문자를 보존하므로 비교도 그대로(정확 일치)다.
DELETE FROM issue_web_link w
USING issue_web_link keep
WHERE keep.issue_id = w.issue_id
  AND keep.url = w.url
  AND keep.id < w.id;

-- ② 제약: url은 VARCHAR(500)이라 UTF-8 최악 2000바이트 + BIGINT — btree 행 한계(약 2704바이트) 안이므로
--    해시 없이 원문으로 건다. 길이를 늘리면 이 판단을 다시 해야 한다.
ALTER TABLE issue_web_link ADD CONSTRAINT uk_issue_web_link_issue_url UNIQUE (issue_id, url);

-- 유니크 인덱스의 선두 컬럼이 issue_id라 이슈별 조회도 이 인덱스로 된다
DROP INDEX idx_web_link_issue;
