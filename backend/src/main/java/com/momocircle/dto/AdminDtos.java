package com.momocircle.dto;

import com.momocircle.domain.enums.PlatformRole;

import java.time.Instant;

public final class AdminDtos {

    private AdminDtos() {}

    public record AdminUserResponse(
            String id,
            String email,
            String fullName,
            String phoneNumber,
            PlatformRole role,
            boolean active,
            Instant createdAt
    ) {}

    public record AuditLogResponse(
            String id,
            String actorName,
            String action,
            String targetType,
            String targetId,
            String details,
            Instant createdAt
    ) {}

    public record PlatformStatsResponse(
            long totalUsers,
            long activeUsers,
            long totalCircles,
            long totalBusinesses,
            long totalOrders,
            long successfulPayments,
            long failedPayments,
            java.math.BigDecimal contributionVolume,
            java.math.BigDecimal marketGmv
    ) {}
}
