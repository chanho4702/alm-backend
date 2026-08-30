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
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getIssueId(),
                notification.getIssueKey(),
                notification.getActorId(),
                notification.getType(),
                notification.getDetail(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
