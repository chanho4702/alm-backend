package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueWebLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IssueWebLinkRepository extends JpaRepository<IssueWebLink, Long> {
    List<IssueWebLink> findByIssueIdOrderByIdDesc(long issueId);
    Optional<IssueWebLink> findByIssueIdAndUrl(long issueId, String url);

    /**
     * 없을 때만 넣는다 — 이미 같은 (issue_id, url)이 있으면 0을 돌려준다. 유니크 위반을 예외로 받으면
     * Postgres가 트랜잭션 전체를 중단시켜 기존 행을 다시 읽을 수 없으므로 ON CONFLICT로 피한다.
     * 동시 삽입 중인 행이 있으면 그 트랜잭션이 끝날 때까지 기다린 뒤 판정한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO issue_web_link (issue_id, url, title, kind, created_by, created_at)"
            + " VALUES (:issueId, :url, :title, :kind, :createdBy, :createdAt) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(long issueId, String url, String title, String kind, long createdBy, Instant createdAt);

    /** PR 상태(V45) — 주어진 이슈들의 같은 URL PR 링크. 바뀐 행만 고친다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update IssueWebLink w set w.state = :state
            where w.url = :url and w.kind = 'PR' and w.issueId in :issueIds and (w.state is null or w.state <> :state)
            """)
    int updatePullRequestState(String url, java.util.Collection<Long> issueIds, String state);
}
