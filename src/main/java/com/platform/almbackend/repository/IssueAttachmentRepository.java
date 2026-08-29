package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueAttachmentRepository extends JpaRepository<IssueAttachment, Long> {
    List<IssueAttachment> findByIssueIdOrderByCreatedAtAscIdAsc(long issueId);

    List<IssueAttachment> findByProjectId(long projectId);
}
