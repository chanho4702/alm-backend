package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 프로젝트가 배정받은 스킴 + (있으면) 프로젝트만의 커스텀 본문 */
@Entity
@Table(name = "project_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectSettings {
    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "scheme_id", nullable = false, length = 40)
    private String schemeId;

    @Column(name = "custom_body", columnDefinition = "text")
    private String customBody;

    public static ProjectSettings of(long projectId, String schemeId) {
        ProjectSettings settings = new ProjectSettings();
        settings.projectId = projectId;
        settings.schemeId = schemeId;
        settings.customBody = null;
        return settings;
    }

    public void assign(String schemeId) {
        this.schemeId = schemeId;
        this.customBody = null;
    }

    public void customize(String body) { this.customBody = body; }
    public void dropCustom() { this.customBody = null; }
    public boolean isCustom() { return customBody != null; }
}
