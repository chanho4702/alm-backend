package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "issue_number", nullable = false, updatable = false)
    private Long issueNumber;

    @Column(name = "issue_key", nullable = false, unique = true, length = 40, updatable = false)
    private String key;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 20)
    private IssueType type;

    /** 커스텀 워크플로 상태 ID. 카테고리 enum으로 고정하지 않는다. */
    @Column(nullable = false, length = 80)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssuePriority priority;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Issue of(
            long projectId,
            long issueNumber,
            String key,
            String title,
            String description,
            IssueType type,
            String status,
            IssuePriority priority,
            Long assigneeId,
            long reporterId) {
        Issue issue = new Issue();
        issue.projectId = projectId;
        issue.issueNumber = issueNumber;
        issue.key = key;
        issue.title = title;
        issue.description = description;
        issue.type = type;
        issue.status = status;
        issue.priority = priority;
        issue.assigneeId = assigneeId;
        issue.reporterId = reporterId;
        issue.version = 1;
        return issue;
    }

    public void edit(
            String title,
            String description,
            IssueType type,
            String status,
            IssuePriority priority,
            Long assigneeId) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (type != null) this.type = type;
        if (status != null) this.status = status;
        if (priority != null) this.priority = priority;
        this.assigneeId = assigneeId;
        this.version += 1;
    }
}

