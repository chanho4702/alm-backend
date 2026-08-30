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

import java.time.Instant;

/** 컴포넌트 — 프로젝트 하위 구성 단위. 기본 담당자 규칙: project(프로젝트 규칙) | lead(컴포넌트 리더) | unassigned */
@Entity
@Table(name = "component")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Component {
    public static final String ASSIGNEE_PROJECT = "project";
    public static final String ASSIGNEE_LEAD = "lead";
    public static final String ASSIGNEE_UNASSIGNED = "unassigned";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "default_assignee", nullable = false, length = 16)
    private String defaultAssignee;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Component of(long projectId, String name, String description, Long leadId, String defaultAssignee, Instant createdAt) {
        Component component = new Component();
        component.projectId = projectId;
        component.name = name;
        component.description = description == null ? "" : description;
        component.leadId = leadId;
        component.defaultAssignee = defaultAssignee == null ? ASSIGNEE_PROJECT : defaultAssignee;
        component.createdAt = createdAt;
        return component;
    }

    public void rename(String name) { this.name = name; }
    public void describe(String description) { this.description = description == null ? "" : description; }
    public void assignLead(Long leadId) { this.leadId = leadId; }
    public void changeDefaultAssignee(String defaultAssignee) { this.defaultAssignee = defaultAssignee; }
}
