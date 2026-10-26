package com.momocircle.dto;

import com.momocircle.domain.enums.NotificationType;

import java.time.Instant;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationResponse(
            String id,
            NotificationType type,
            String message,
            String referenceId,
            boolean read,
            Instant createdAt
    ) {}

    public record UnreadCountResponse(long unread) {}
}
