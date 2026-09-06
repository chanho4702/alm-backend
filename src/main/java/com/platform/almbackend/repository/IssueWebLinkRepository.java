package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueWebLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssueWebLinkRepository extends JpaRepository<IssueWebLink, Long> {
    List<IssueWebLink> findByIssueIdOrderByIdDesc(long issueId);
    Optional<IssueWebLink> findByIssueIdAndUrl(long issueId, String url);
}
