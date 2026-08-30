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

/** 프로젝트 바로 가기 — 사이드바에 두는 외부 링크(위키·저장소·대시보드) */
@Entity
@Table(name = "project_shortcut")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectShortcut {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ProjectShortcut of(long projectId, String name, String url, int sortOrder, Instant createdAt) {
        ProjectShortcut shortcut = new ProjectShortcut();
        shortcut.projectId = projectId;
        shortcut.name = name;
        shortcut.url = url;
        shortcut.sortOrder = sortOrder;
        shortcut.createdAt = createdAt;
        return shortcut;
    }

    public void edit(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
