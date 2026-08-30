package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 워크플로/이슈 타입 스킴 — 본문은 JSON 문서(statuses·transitions·layout·enabledTypes) */
@Entity
@Table(name = "settings_scheme")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingsScheme {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    public static SettingsScheme of(String id, String name, String body) {
        SettingsScheme scheme = new SettingsScheme();
        scheme.id = id;
        scheme.name = name;
        scheme.isDefault = false;
        scheme.body = body;
        return scheme;
    }

    public void rename(String name) { this.name = name; }
    public void replaceBody(String body) { this.body = body; }
    public void setDefault(boolean value) { this.isDefault = value; }
}
