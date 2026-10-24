package com.platform.almbackend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "알림 인박스 한 쪽(V40) — (정렬 시각, id) 내림차순 키셋 페이징")
public record NotificationInboxResponse(
        @Schema(description = "알림 목록(최신 순 — 미루기에서 돌아온 알림은 해제 시각 기준)")
        List<NotificationResponse> items,
        @Schema(description = "다음 쪽 커서. 더 없으면 null. 불투명 문자열로 그대로 돌려준다", example = "1729760000000000_123")
        String nextCursor,
        @Schema(description = "보이는(보관 아님·미뤄지지 않은) 읽지 않은 알림 수 — 필터와 무관한 배지 값", example = "4")
        long unreadCount) {}
