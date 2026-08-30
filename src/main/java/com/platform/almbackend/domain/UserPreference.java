package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 개인 설정 — JSON 문서 한 덩이. 서버는 알림·자동 관찰 규칙에만 읽고, 나머지는 프론트 몫 */
@Entity
@Table(name = "user_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserPreference of(long userId, String body, Instant at) {
        UserPreference preference = new UserPreference();
        preference.userId = userId;
        preference.body = body;
        preference.updatedAt = at;
        return preference;
    }

    public void replace(String body, Instant at) {
        this.body = body;
        this.updatedAt = at;
    }
}
