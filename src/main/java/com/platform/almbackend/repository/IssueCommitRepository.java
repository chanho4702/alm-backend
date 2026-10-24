package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface IssueCommitRepository extends JpaRepository<IssueCommit, Long> {
    /** 없을 때만 잇는다 — 이미 이어져 있으면 0(멱등) */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO issue_commit (issue_id, commit_id, linked_by, linked_at)"
            + " VALUES (:issueId, :commitId, :linkedBy, :linkedAt) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(long issueId, long commitId, long linkedBy, Instant linkedAt);

    List<IssueCommit> findByCommitIdIn(Collection<Long> commitIds);
}
