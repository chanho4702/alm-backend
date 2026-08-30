package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueWatcher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueWatcherRepository extends JpaRepository<IssueWatcher, IssueWatcher.Key> {
    List<IssueWatcher> findByIssueIdOrderByCreatedAtAsc(long issueId);

    boolean existsByIssueIdAndUserId(long issueId, long userId);

    void deleteByIssueIdAndUserId(long issueId, long userId);
}
