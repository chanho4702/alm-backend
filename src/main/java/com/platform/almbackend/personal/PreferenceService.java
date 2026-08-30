package com.platform.almbackend.personal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.UserPreference;
import com.platform.almbackend.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Transactional(readOnly = true)
    public PreferenceBody get(long userId) {
        return preferences.findById(userId).map(p -> parse(p.getBody())).orElse(PreferenceBody.defaults());
    }

    public PreferenceBody save(long userId, PreferenceBody body) {
        PreferenceBody filled = (body == null ? PreferenceBody.defaults() : body).filled();
        if (!START_PAGES.contains(filled.startPage())) {
            throw new IllegalArgumentException("시작 화면은 home/projects/last-project 중 하나입니다");
        }
        String serialized = serialize(filled);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        preferences.findById(userId).ifPresentOrElse(
                p -> p.replace(serialized, now),
                () -> preferences.save(UserPreference.of(userId, serialized, now)));
        return filled;
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
