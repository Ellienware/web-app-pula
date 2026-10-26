package com.momocircle.dto;

import com.momocircle.domain.enums.ActivityType;

import java.time.Instant;

public final class ActivityDtos {

    private ActivityDtos() {}

    public record ActivityResponse(
            String id,
            String circleId,
            String actorId,
            String actorName,
            ActivityType type,
            String message,
            Instant createdAt
    ) {}
}
