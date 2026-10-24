package com.platform.almbackend.notification.dto;

import com.platform.almbackend.domain.Notification;

import java.time.Instant;

public record NotificationResponse(
        long id,
        Long issueId,
        String issueKey,
        long actorId,
        Notification.Type type,
        String detail,
        boolean read,
        Instant createdAt,
        /** 미루기 해제 시각(V40) — 지났으면 인박스로 돌아온 알림이다. 미룬 적 없으면 null */
        Instant snoozedUntil,
        /** 보관 시각(V40). 보관 안 했으면 null */
        Instant archivedAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getIssueId(),
                notification.getIssueKey(),
                notification.getActorId(),
                notification.getType(),
                notification.getDetail(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getSnoozedUntil(),
                notification.getArchivedAt());
    }
}
