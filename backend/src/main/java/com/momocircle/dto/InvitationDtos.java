package com.momocircle.dto;

import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.InvitationStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class InvitationDtos {

    private InvitationDtos() {}

    public record CreateInvitationRequest(
            @NotBlank @Email String email,
            CircleRole role
    ) {}

    public record RedeemInvitationRequest(
            @NotBlank String token
    ) {}

    public record InvitationResponse(
            String id,
            String circleId,
            String circleName,
            String invitedEmail,
            String invitedByName,
            CircleRole role,
            String token,
            InvitationStatus status,
            Instant expiresAt,
            Instant createdAt
    ) {}
}
