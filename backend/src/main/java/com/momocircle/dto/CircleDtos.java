package com.momocircle.dto;

import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public final class CircleDtos {

    private CircleDtos() {}

    public record CreateCircleRequest(
            @NotBlank String name,
            String description,
            @NotNull CircleType type,
            @NotBlank String currency,
            @Positive BigDecimal goalAmount
    ) {}

    public record AddMemberRequest(
            @NotBlank String email,
            CircleRole role
    ) {}

    public record ChangeRoleRequest(
            @NotNull CircleRole role
    ) {}

    public record MemberResponse(
            String membershipId,
            String userId,
            String fullName,
            String email,
            CircleRole role
    ) {}

    /** Circle with computed progress metrics. */
    public record CircleResponse(
            String id,
            String name,
            String description,
            CircleType type,
            String currency,
            BigDecimal goalAmount,
            BigDecimal totalContributed,
            Integer progressPercent,
            long memberCount,
            String ownerId,
            CircleRole viewerRole
    ) {}
}
