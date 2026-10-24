package com.platform.almbackend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Schema(description = "알림 미루기(V40) — 미래 시각, 최대 1년 뒤")
public record NotificationSnoozeRequest(
        @Schema(description = "다시 보일 시각(ISO-8601)", example = "2026-10-25T09:00:00+09:00")
        @NotNull(message = "until이 필요합니다")
        Instant until) {}
