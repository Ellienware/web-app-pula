package com.momocircle.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contracts for stokvel discovery, invite links and membership requests. None of these carries a balance, a total, another
 * member's contact details or any internal id other than the circle id the existing stokvel APIs already use.
 * PULA tracks stokvel contributions; it does not hold or pool the money, and every response that describes a stokvel says so.
 */
public final class StokvelMembershipDtos {

    private StokvelMembershipDtos() { }

    public static final String FUNDS_NOTICE =
            "PULA tracks contributions for the group. It does not hold, pool or pay out the group's money.";

    // ---------------------------------------------------------------- membership settings (owner/admin)

    public record MembershipSettingsView(boolean discoverable, String area, String publicSummary, String membershipRequirements,
                                         Integer maxMembers, boolean inviteRequiresApproval, int memberCount, int pendingRequests) { }

    public record UpdateMembershipSettings(
            @NotNull Boolean discoverable,
            @Size(max = 80) String area,
            @Size(max = 300) String publicSummary,
            @Size(max = 500) String membershipRequirements,
            @Min(2) @Max(500) Integer maxMembers,
            @NotNull Boolean inviteRequiresApproval) { }

    // ---------------------------------------------------------------- discovery

    public record DiscoverableStokvel(String circleId, String name, String organiser, String stokvelType, String frequency,
                                      BigDecimal contributionAmount, String currency, String publicSummary, String area,
                                      String requirements, int memberCount, Integer maxMembers, Integer spotsLeft, boolean full,
                                      String joinState, String pendingRequestId, String notice) { }

    public record PageView<T>(java.util.List<T> items, int page, int size, long totalItems, int totalPages) { }

    // ---------------------------------------------------------------- join requests

    public record JoinRequestCreate(@Size(max = 300) String message) { }

    public record DecisionRequest(@Size(max = 300) String note) { }

    public record JoinRequestView(String requestId, String circleId, String stokvelName, String applicantName, String status,
                                  String source, String message, Instant createdAt, Instant decidedAt, String decidedByName,
                                  String decisionNote, boolean canCancel) { }

    // ---------------------------------------------------------------- invite links

    public record CreateInviteLink(@Min(1) @Max(30) Integer expiresInDays, @Min(1) @Max(100) Integer maxUses) { }

    /** Status is ACTIVE, EXPIRED, REVOKED or EXHAUSTED. The code itself is never part of a listing. */
    public record InviteLinkView(String linkId, String codeHint, String status, int uses, int maxUses, Instant expiresAt,
                                 Instant createdAt, boolean requiresApproval, String createdBy, Instant revokedAt) { }

    /** Returned once, at creation: {@code code} cannot be retrieved again. */
    public record CreatedInviteLink(InviteLinkView link, String code, String notice) { }

    public record CodeRequest(@NotBlank @Size(max = 64) String code) { }

    public record InvitePreview(String stokvelName, String organiser, String stokvelType, String frequency, BigDecimal contributionAmount,
                                String currency, String publicSummary, String requirements, int memberCount, Integer maxMembers,
                                boolean requiresApproval, Instant expiresAt, boolean alreadyMember, String notice) { }

    /** outcome: JOINED, REQUESTED, ALREADY_MEMBER or ALREADY_REQUESTED. */
    public record AcceptInviteResult(String outcome, String stokvelId, String stokvelName, JoinRequestView request) { }
}
