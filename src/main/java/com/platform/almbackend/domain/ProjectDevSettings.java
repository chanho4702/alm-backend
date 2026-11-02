package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 프로젝트 개발 설정(V43) — git 브랜치 이름 접두. 행이 없으면 접두 없음. 검증은 서비스가 한다 */
@Entity
@Table(name = "project_dev_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectDevSettings {
    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "branch_prefix", nullable = false, length = 50)
    private String branchPrefix;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProjectDevSettings of(long projectId) {
        ProjectDevSettings settings = new ProjectDevSettings();
        settings.projectId = projectId;
        settings.branchPrefix = "";
        settings.version = 0;
        return settings;
    }

    public void apply(String branchPrefix, Instant now) {
        this.branchPrefix = branchPrefix;
        this.updatedAt = now;
        this.version += 1;
    }
}
