package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 전역 설정 key → JSON 문서 (공지 배너 등) */
@Entity
@Table(name = "system_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemSetting {
    @Id
    @Column(name = "setting_key", length = 60)
    private String key;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SystemSetting of(String key, String body, Instant at) {
        SystemSetting setting = new SystemSetting();
        setting.key = key;
        setting.body = body;
        setting.updatedAt = at;
        return setting;
    }

    public void replace(String body, Instant at) {
        this.body = body;
        this.updatedAt = at;
    }
}
