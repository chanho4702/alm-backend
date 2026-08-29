package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sprint_id")
    private Long sprintId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private IssueResolution resolution;

    /** 수정 버전(fix version). null = 미지정 */
    @Column(name = "fix_version_id")
    private Long fixVersionId;

    @Column(name = "estimate_hours", precision = 10, scale = 2)
    private BigDecimal estimateHours;

    @ElementCollection
    @CollectionTable(name = "issue_label", joinColumns = @JoinColumn(name = "issue_id"))
    @OrderColumn(name = "label_order")
    @Column(name = "label", nullable = false, length = 80)
    private List<String> labels = new ArrayList<>();

    @Column(name = "sort_order", nullable = false)
    private Long sortOrder;

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
        return of(projectId, issueNumber, key, title, description, type, status, priority,
                assigneeId, reporterId, null, null, null, null, List.of(), 1L);
    }

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
            long reporterId,
            Long parentId,
            Long sprintId,
            LocalDate dueDate,
            BigDecimal estimateHours,
            List<String> labels,
            long sortOrder) {
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
        issue.parentId = parentId;
        issue.sprintId = sprintId;
        issue.dueDate = dueDate;
        issue.estimateHours = estimateHours;
        issue.labels = new ArrayList<>(labels);
        issue.sortOrder = sortOrder;
        issue.version = 1;
        return issue;
    }

    public void edit(
            String title,
            String description,
            IssueType type,
            String status,
            IssuePriority priority,
            Long assigneeId,
            Long parentId,
            Long sprintId,
            LocalDate dueDate,
            BigDecimal estimateHours,
            IssueResolution resolution,
            Long fixVersionId,
            List<String> labels,
            long sortOrder) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (type != null) this.type = type;
        if (status != null) this.status = status;
        if (priority != null) this.priority = priority;
        this.assigneeId = assigneeId;
        this.parentId = parentId;
        this.sprintId = sprintId;
        this.dueDate = dueDate;
        this.estimateHours = estimateHours;
        this.resolution = resolution;
        this.fixVersionId = fixVersionId;
        this.labels.clear();
        this.labels.addAll(labels);
        this.sortOrder = sortOrder;
        this.version += 1;
    }

    /**
     * 보드 이동 — 상태와 그룹 내 순서만 바꾼다. 정렬은 사용자가 편집 폼에서 보고 있던 값이
     * 아니므로 `version`을 올리지 않는다. 올리면 드래그 한 번이 남의 편집 저장을 409로 만든다.
     */
    public void moveTo(String status, long sortOrder) {
        this.status = status;
        this.sortOrder = sortOrder;
    }

    /** 백로그/스프린트 랭크 이동 — 스프린트 소속과 그룹 내 순서만 바꾼다. */
    public void rankTo(Long sprintId, long sortOrder) {
        this.sprintId = sprintId;
        this.sortOrder = sortOrder;
    }

    /** 같은 그룹의 다른 이슈들을 1..n으로 다시 매길 때 쓴다. */
    public void resequence(long sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** 스프린트 완료 시 미완료 이슈를 백로그로 되돌린다. */
    public void moveToBacklog(long sortOrder) {
        this.sprintId = null;
        this.sortOrder = sortOrder;
    }

    /** 릴리스 시 미완료 이슈를 다음 버전으로 넘긴다 — 사용자가 편집 폼에서 보던 값이 아니라 version은 올리지 않는다. */
    public void assignFixVersion(Long fixVersionId) {
        this.fixVersionId = fixVersionId;
    }

    /** 스프린트 완료 시 미완료 이슈를 다음 스프린트로 넘긴다. */
    public void moveToSprint(long sprintId, long sortOrder) {
        this.sprintId = sprintId;
        this.sortOrder = sortOrder;
    }
}
