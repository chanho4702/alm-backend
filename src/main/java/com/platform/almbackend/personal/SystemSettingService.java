package com.platform.almbackend.personal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.SystemSetting;
import com.platform.almbackend.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** 전역 설정 — 지금은 공지 배너(지라 시스템 > 사용자 인터페이스 > 공지 배너) */
@Service
@RequiredArgsConstructor
@Transactional
public class SystemSettingService {
    static final String BANNER = "banner";
    private static final Set<String> LEVELS = Set.of("info", "warning");

    private final SystemSettingRepository settings;
    private final ObjectMapper json;

    public record Banner(boolean enabled, String level, String message) {
        static Banner off() { return new Banner(false, "info", ""); }
    }

    @Transactional(readOnly = true)
    public Banner banner() {
        return settings.findById(BANNER).map(s -> {
            try {
                return json.readValue(s.getBody(), Banner.class);
            } catch (JsonProcessingException e) {
                return Banner.off();
            }
        }).orElse(Banner.off());
    }

    public Banner saveBanner(Banner banner) {
        String level = banner.level() == null ? "info" : banner.level();
        if (!LEVELS.contains(level)) throw new IllegalArgumentException("배너 수준은 info/warning 중 하나입니다");
        String message = banner.message() == null ? "" : banner.message().trim();
        if (banner.enabled() && message.isEmpty()) throw new IllegalArgumentException("배너 내용을 입력하세요");
        if (message.length() > 500) throw new IllegalArgumentException("배너 내용은 500자 이하여야 합니다");
        Banner normalized = new Banner(banner.enabled(), level, message);
        String body;
        try {
            body = json.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("배너를 저장할 수 없습니다");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        settings.findById(BANNER).ifPresentOrElse(
                s -> s.replace(body, now),
                () -> settings.save(SystemSetting.of(BANNER, body, now)));
        return normalized;
    }
}
