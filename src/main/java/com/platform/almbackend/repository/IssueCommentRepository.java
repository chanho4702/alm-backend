package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {
    List<IssueComment> findByIssueIdOrderByCreatedAtAscIdAsc(long issueId);
}
