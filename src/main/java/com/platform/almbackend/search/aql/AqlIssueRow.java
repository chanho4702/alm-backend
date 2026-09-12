package com.platform.almbackend.search.aql;

import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.issue.dto.IssueResponse;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * AQL 검색 전용 읽기 모델 — {@code issue} 테이블을 {@link com.platform.almbackend.domain.Issue}와 같은
 * 컬럼으로 한 번 더 매핑한다.
 *
 * <p>왜 두 번 매핑하는가: {@code Issue}에는 {@code @SQLRestriction("archived_at is null")}이 걸려 있어
 * Criteria 질의가 보관된 행을 절대 보지 못한다. AQL은 {@code archived = true}로 보관함을 검색할 수 있어야
 * 하는데, 제한이 붙은 루트에 {@code archived_at is not null}을 더하면 모순이라 항상 0건이 된다. 그래서
 * 제한 없는 읽기 전용 엔티티를 따로 두고, 기본값(보관 제외)은 AQL이 직접 술어로 붙인다.
 *
 * <p>{@link Immutable}이라 이 매핑으로는 아무것도 쓰지 않는다 — 쓰기는 전부 {@code Issue}가 한다.
 */
@Entity
@Table(name = "issue")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AqlIssueRow {

    @Id
    private Long id;

    @Column(name = "project_id", nullable = false, insertable = false, updatable = false)
    private Long projectId;

    @Column(name = "issue_number", nullable = false, insertable = false, updatable = false)
    private Long issueNumber;

    @Column(name = "issue_key", nullable = false, length = 40, insertable = false, updatable = false)
    private String key;

    @Column(nullable = false, length = 300, insertable = false, updatable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text", insertable = false, updatable = false)
    private String description;

    @Column(name = "issue_type", nullable = false, length = 40, insertable = false, updatable = false)
    private String type;

    @Column(nullable = false, length = 80, insertable = false, updatable = false)
    private String status;

    @Column(nullable = false, length = 40, insertable = false, updatable = false)
    private String priority;

    @Column(name = "archived_at", insertable = false, updatable = false)
    private Instant archivedAt;

    @Column(name = "assignee_id", insertable = false, updatable = false)
    private Long assigneeId;

    @Column(name = "reporter_id", nullable = false, insertable = false, updatable = false)
    private Long reporterId;

    @Column(name = "parent_id", insertable = false, updatable = false)
    private Long parentId;

    @Column(name = "sprint_id", insertable = false, updatable = false)
    private Long sprintId;

    @Column(name = "due_date", insertable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, insertable = false, updatable = false)
    private IssueResolution resolution;

    @Column(name = "resolved_at", insertable = false, updatable = false)
    private Instant resolvedAt;

    @Column(name = "fix_version_id", insertable = false, updatable = false)
    private Long fixVersionId;

    @Column(name = "estimate_hours", precision = 10, scale = 2, insertable = false, updatable = false)
    private BigDecimal estimateHours;

    @ElementCollection
    @CollectionTable(name = "issue_label", joinColumns = @JoinColumn(name = "issue_id"))
    @OrderColumn(name = "label_order")
    @Column(name = "label", nullable = false, length = 80, insertable = false, updatable = false)
    private List<String> labels = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "issue_component", joinColumns = @JoinColumn(name = "issue_id"))
    @OrderColumn(name = "component_order")
    @Column(name = "component_id", nullable = false, insertable = false, updatable = false)
    private List<Long> componentIds = new ArrayList<>();

    @Column(name = "sort_order", nullable = false, insertable = false, updatable = false)
    private Long sortOrder;

    @Column(nullable = false, insertable = false, updatable = false)
    private Integer version;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** 검색 응답은 기존 이슈 shape 그대로 — 프론트 어댑터가 하나만 있으면 되게 한다 */
    public IssueResponse toResponse() {
        return new IssueResponse(id, key, projectId, title, description, type, status, priority,
                assigneeId, reporterId, parentId, sprintId, dueDate, estimateHours, resolution, fixVersionId,
                List.copyOf(labels), List.copyOf(componentIds), sortOrder, version, createdAt, updatedAt, archivedAt,
                resolvedAt);
    }
}
