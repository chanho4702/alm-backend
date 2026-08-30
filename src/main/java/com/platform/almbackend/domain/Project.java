package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "project")
@SQLRestriction("deleted_at is null") // 휴지통 프로젝트는 일반 조회에서 빠진다 — 휴지통은 네이티브 쿼리로 읽는다
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    public static final String ASSIGNEE_UNASSIGNED = "unassigned";
    public static final String ASSIGNEE_LEAD = "lead";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key", nullable = false, unique = true, length = 12, updatable = false)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 60)
    private String category = "";

    /** 프로젝트 리더 — 기본 담당자 후보. 생성자가 기본값 */
    @Column(name = "lead_id")
    private Long leadId;

    /** unassigned | lead — 담당자 없이 만든 이슈에 적용 */
    @Column(name = "default_assignee", nullable = false, length = 16)
    private String defaultAssignee = ASSIGNEE_UNASSIGNED;

    @Column(nullable = false, length = 40)
    private String icon = "";

    @Column(nullable = false, length = 20)
    private String color = "";

    @Column(nullable = false, length = 500)
    private String url = "";

    /** 보관 — 읽기 전용, 목록에는 남는다 */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** 휴지통 — 조회에서 빠지고 복원·영구 삭제만 가능 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_issue_number", nullable = false)
    private Long lastIssueNumber;

    /** 프론트가 보고 있던 버전을 명시적으로 비교하는 수동 낙관적 락. */
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Project of(String key, String name, String description) {
        Project project = new Project();
        project.key = key;
        project.name = name;
        project.description = description;
        project.lastIssueNumber = 0L;
        project.version = 1;
        return project;
    }

    public void edit(String name, String description) {
        this.name = name;
        this.description = description;
        this.version += 1;
    }

    /** 세부 사항(지라 프로젝트 설정 > 세부) — null은 "그대로" */
    public void editDetails(String category, Long leadId, boolean clearLead, String defaultAssignee,
                            String icon, String color, String url) {
        if (category != null) this.category = category;
        if (clearLead) this.leadId = null;
        else if (leadId != null) this.leadId = leadId;
        if (defaultAssignee != null) this.defaultAssignee = defaultAssignee;
        if (icon != null) this.icon = icon;
        if (color != null) this.color = color;
        if (url != null) this.url = url;
    }

    public boolean isArchived() { return archivedAt != null; }
    public void archive(Instant at) { this.archivedAt = at; }
    public void unarchive() { this.archivedAt = null; }
    public void trash(Instant at) { this.deletedAt = at; }
    public void restoreFromTrash() { this.deletedAt = null; }

    public void assignLead(Long leadId) {
        this.leadId = leadId;
    }

    /** 담당자 없이 만든 이슈의 담당자 — 기본 담당자 규칙 적용 */
    public Long resolveDefaultAssignee() {
        return ASSIGNEE_LEAD.equals(defaultAssignee) ? leadId : null;
    }

    /** 프로젝트 row를 비관적으로 잠근 서비스 안에서만 호출한다. */
    public long nextIssueNumber() {
        this.lastIssueNumber += 1;
        return this.lastIssueNumber;
    }

    /** 보존 키(이관)가 쓴 번호 이상으로 카운터를 앞당긴다 — 이후 발급 키가 겹치지 않게 */
    public void reserveIssueNumber(long number) {
        if (number > this.lastIssueNumber) this.lastIssueNumber = number;
    }
}
