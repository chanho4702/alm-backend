package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueActivityRepository extends JpaRepository<IssueActivity, Long> {
    List<IssueActivity> findByIssueIdOrderByOccurredAtAscIdAsc(long issueId);
}
