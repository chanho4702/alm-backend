package com.platform.almbackend.personal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.UserPreference;
import com.platform.almbackend.notification.EmailNotifier;
import com.platform.almbackend.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * 개인 설정(지라 개인 설정 > 일반·알림). 알림 이벤트별 on/off와 자동 관찰은 서버가 강제하고,
 * 시작 화면 같은 프론트 전용 값은 그대로 저장만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PreferenceService {
    private static final Set<String> START_PAGES = Set.of("home", "projects", "last-project");

    private final UserPreferenceRepository preferences;
    private final ObjectMapper json;
    /** EmailNotifier가 이 서비스를 쓴다(수신 주소) — 순환을 끊으려고 지연 주입 */
    private final ObjectProvider<EmailNotifier> email;

    /** 알림 종류별 제품 내 알림 수신 여부 */
    public record NotificationPrefs(Boolean assigned, Boolean statusChanged, Boolean commented, Boolean mentioned) {
        public boolean assignedOn() { return assigned == null || assigned; }
        public boolean statusChangedOn() { return statusChanged == null || statusChanged; }
        public boolean commentedOn() { return commented == null || commented; }
        /** 코멘트·설명에서 @멘션됐을 때 — 워처가 아니어도 받는다 */
        public boolean mentionedOn() { return mentioned == null || mentioned; }
        static NotificationPrefs defaults() { return new NotificationPrefs(true, true, true, true); }
        NotificationPrefs filled() {
            return new NotificationPrefs(assignedOn(), statusChangedOn(), commentedOn(), mentionedOn());
        }
    }

    /** 자동 관찰 — 내가 만든(true)·댓글 단(true)·수정한(false) 이슈를 자동으로 관찰한다(지라 기본값) */
    public record AutoWatch(Boolean created, Boolean commented, Boolean edited) {
        public boolean createdOn() { return created == null || created; }
        public boolean commentedOn() { return commented == null || commented; }
        public boolean editedOn() { return edited != null && edited; }
        static AutoWatch defaults() { return new AutoWatch(true, true, false); }
        AutoWatch filled() { return new AutoWatch(createdOn(), commentedOn(), editedOn()); }
    }

    public record PreferenceBody(NotificationPrefs notifications, AutoWatch autoWatch, String startPage) {
        public static PreferenceBody defaults() {
            return new PreferenceBody(NotificationPrefs.defaults(), AutoWatch.defaults(), "home");
        }
        PreferenceBody filled() {
            return new PreferenceBody(
                    (notifications == null ? NotificationPrefs.defaults() : notifications).filled(),
                    (autoWatch == null ? AutoWatch.defaults() : autoWatch).filled(),
                    startPage == null || startPage.isBlank() ? "home" : startPage);
        }
    }

    /** 개인 설정 응답 — 저장 문서 + 이메일 스위치 + 서버 메일 구성 여부(읽기 전용) */
    public record PreferenceView(
            NotificationPrefs notifications,
            AutoWatch autoWatch,
            String startPage,
            boolean emailEnabled,
            /** 서버에 메일 서버가 설정돼 있는가 — false면 스위치를 켜도 메일이 나가지 않는다 */
            boolean mailConfigured) {
    }

    /** 개인 설정 요청 — mailConfigured는 서버가 정한다(요청에 실려도 무시) */
    public record PreferenceUpdate(
            NotificationPrefs notifications, AutoWatch autoWatch, String startPage, Boolean emailEnabled) {
        PreferenceBody body() {
            return new PreferenceBody(notifications, autoWatch, startPage);
        }
    }

    /** 서버가 규칙에 쓰는 저장 문서만 — 알림·자동 관찰 판단에 쓴다 */
    @Transactional(readOnly = true)
    public PreferenceBody get(long userId) {
        return preferences.findById(userId).map(p -> parse(p.getBody())).orElse(PreferenceBody.defaults());
    }

    /** 설정 화면 — 여는 김에 주소 스냅샷을 갱신한다(설정을 한 번도 저장하지 않은 사람도 메일을 받게) */
    public PreferenceView view(long userId, String jwtEmail) {
        UserPreference stored = preferences.findById(userId).orElse(null);
        if (stored != null) {
            stored.rememberEmail(jwtEmail);
            return toView(parse(stored.getBody()), stored.isEmailEnabled());
        }
        return toView(PreferenceBody.defaults(), false);
    }

    public PreferenceView save(long userId, String jwtEmail, PreferenceUpdate request) {
        PreferenceUpdate req = request == null
                ? new PreferenceUpdate(null, null, null, null)
                : request;
        PreferenceBody filled = req.body().filled();
        if (!START_PAGES.contains(filled.startPage())) {
            throw new IllegalArgumentException("시작 화면은 home/projects/last-project 중 하나입니다");
        }
        String serialized = serialize(filled);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UserPreference stored = preferences.findById(userId)
                .orElseGet(() -> preferences.save(UserPreference.of(userId, serialized, now)));
        stored.replace(serialized, now);
        stored.rememberEmail(jwtEmail);
        // 필드를 안 보내면 기존 값을 유지한다 — 기존 프론트 요청(emailEnabled 없음)이 스위치를 끄지 않게
        if (req.emailEnabled() != null) stored.setEmailEnabled(req.emailEnabled());
        return toView(filled, stored.isEmailEnabled());
    }

    /**
     * 알림함을 열 때마다 주소를 갱신한다 — 설정 화면을 한 번도 열지 않은 사용자의 주소도 알아야
     * 스위치를 켠 순간부터 메일이 나간다. 행이 없으면 만들지 않는다(스위치는 여전히 꺼짐).
     */
    public void rememberEmail(long userId, String jwtEmail) {
        if (jwtEmail == null || jwtEmail.isBlank()) return;
        preferences.findById(userId).ifPresent(p -> p.rememberEmail(jwtEmail));
    }

    /** 이메일 알림을 받을 주소 — 스위치가 꺼졌거나 주소를 모르면 empty */
    @Transactional(readOnly = true)
    public Optional<String> emailRecipient(long userId) {
        return preferences.findById(userId)
                .filter(UserPreference::isEmailEnabled)
                .map(UserPreference::getEmail)
                .filter(address -> !address.isBlank());
    }

    private PreferenceView toView(PreferenceBody body, boolean emailEnabled) {
        return new PreferenceView(
                body.notifications(), body.autoWatch(), body.startPage(), emailEnabled,
                email.getObject().configured());
    }

    private PreferenceBody parse(String body) {
        try {
            return json.readValue(body, PreferenceBody.class).filled();
        } catch (JsonProcessingException e) {
            return PreferenceBody.defaults();
        }
    }

    private String serialize(PreferenceBody body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("개인 설정을 저장할 수 없습니다");
        }
    }
}
