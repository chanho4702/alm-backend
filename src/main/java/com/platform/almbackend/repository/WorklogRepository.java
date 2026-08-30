package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Worklog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorklogRepository extends JpaRepository<Worklog, Long> {
    List<Worklog> findByIssueIdOrderByWorkedOnAscIdAsc(long issueId);
}
