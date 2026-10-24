package com.platform.almbackend.repository;

import com.platform.almbackend.domain.DevCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DevCommitRepository extends JpaRepository<DevCommit, Long> {
    Optional<DevCommit> findByRepositoryUrlAndSha(String repositoryUrl, String sha);

    /**
     * 없을 때만 넣는다(멱등) — 같은 (repository_url, sha)가 있으면 0. 유니크 위반을 예외로 받으면 Postgres가 트랜잭션을
     * 중단시켜 배치의 나머지를 처리할 수 없으므로 ON CONFLICT로 피한다(issue_web_link와 같은 방식).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO dev_commit (repository_url, repository_name, sha, short_sha, title, body_excerpt,"
            + " author_name, author_email, authored_at, branch, url, ingested_by, ingested_at)"
            + " VALUES (:repositoryUrl, :repositoryName, :sha, :shortSha, :title, :bodyExcerpt,"
            + " :authorName, :authorEmail, :authoredAt, :branch, :url, :ingestedBy, :ingestedAt) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(String repositoryUrl, String repositoryName, String sha, String shortSha, String title,
                       String bodyExcerpt, String authorName, String authorEmail, Instant authoredAt, String branch,
                       String url, long ingestedBy, Instant ingestedAt);

    /** 이슈에 이어진 커밋 — 최신 순 */
    @Query("""
            select c from DevCommit c, IssueCommit ic
            where ic.commitId = c.id and ic.issueId = :issueId
            order by c.authoredAt desc, c.id desc
            """)
    List<DevCommit> findLinkedToIssue(long issueId, org.springframework.data.domain.Limit limit);

    @Query("select count(ic) from IssueCommit ic where ic.issueId = :issueId")
    long countLinkedToIssue(long issueId);

    /**
     * 프로젝트 최근 커밋 — 그 프로젝트가 등록한 저장소의 커밋 ∪ 그 프로젝트 이슈(보관 제외)에 이어진 커밋. 최신 순 키셋
     * (authored_at, id). 첫 쪽은 먼 미래 커서로 부른다(null 바인딩을 피한다 — 타입 없는 null 비교).
     */
    @Query(value = """
            select c.* from dev_commit c
            where (c.repository_url in (select r.url from project_code_repository r where r.project_id = :projectId)
                   or exists (select 1 from issue_commit ic join issue i on i.id = ic.issue_id
                              where ic.commit_id = c.id and i.project_id = :projectId and i.archived_at is null))
              and (c.authored_at < :cursorAt or (c.authored_at = :cursorAt and c.id < :cursorId))
            order by c.authored_at desc, c.id desc
            limit :limit
            """, nativeQuery = true)
    List<DevCommit> findProjectRecent(long projectId, Instant cursorAt, long cursorId, int limit);
}
