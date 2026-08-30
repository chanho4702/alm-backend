package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueLinkRepository extends JpaRepository<IssueLink, Long> {
    List<IssueLink> findBySourceIdOrTargetId(long sourceId, long targetId);
    long countByType(String type);
}
