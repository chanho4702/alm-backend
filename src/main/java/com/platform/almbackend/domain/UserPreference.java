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

    /** 이메일 알림 스위치 — 기본 꺼짐. 메일 서버가 없는 설치에서 켜 두면 아무것도 오지 않는다 */
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    /**
     * 마지막으로 다녀갔을 때 본 주소(JWT email 클레임 스냅샷). 발송 시점에는 수신자의 토큰이 없어
     * org 디렉터리 대신 이 스냅샷을 쓴다 — wiki-backend와 같은 방식.
     */
    @Column(length = 320)
    private String email;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserPreference of(long userId, String body, Instant at) {
        UserPreference preference = new UserPreference();
        preference.userId = userId;
        preference.body = body;
        preference.emailEnabled = false;
        preference.updatedAt = at;
        return preference;
    }

    public void replace(String body, Instant at) {
        this.body = body;
        this.updatedAt = at;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    /** 바뀐 것이 없으면 쓰지 않는다 — 알림함을 열 때마다 UPDATE가 나가지 않게 */
    public void rememberEmail(String email) {
        if (email == null || email.isBlank()) return;
        String trimmed = email.trim();
        if (!trimmed.equals(this.email)) this.email = trimmed;
    }
}
