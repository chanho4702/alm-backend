package com.platform.almbackend.dev.scm;

import java.util.Locale;

/**
 * 설치 옵션 {@code ALM_SCM_SYNC} — 저장소 이벤트를 어떻게 받나(메일 {@code MAIL_MODE}·검색 {@code SEARCH_MODE}와 같은 스위치 하나).
 *
 * <ul>
 *   <li>{@code webhook} — Git 서버가 ALM으로 보낸다(사내 Git, 또는 클라우드 Git + 수신 경로 개방). 폴링 없음.</li>
 *   <li>{@code polling} — ALM이 Git API를 주기적으로 읽는다(들어오는 연결이 막힌 폐쇄망). 웹훅 수신 경로는 404.</li>
 *   <li>{@code both}(기본) — 둘 다. 폴링이 웹훅이 놓친 이벤트를 메운다(수집이 멱등이라 겹쳐도 한 번).</li>
 * </ul>
 */
public enum ScmSyncMode {
    WEBHOOK, POLLING, BOTH;

    public boolean webhooks() {
        return this != POLLING;
    }

    public boolean polling() {
        return this != WEBHOOK;
    }

    /** 비면 both. 모르는 값이면 기동을 세운다 — 오타가 조용히 "둘 다 꺼짐"이나 기본값으로 바뀌지 않게 */
    public static ScmSyncMode parse(String raw) {
        if (raw == null || raw.isBlank()) return BOTH;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "webhook" -> WEBHOOK;
            case "polling" -> POLLING;
            case "both" -> BOTH;
            default -> throw new IllegalStateException("ALM_SCM_SYNC는 webhook·polling·both 중 하나여야 합니다: " + raw);
        };
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
