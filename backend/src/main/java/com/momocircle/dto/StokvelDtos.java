package com.momocircle.dto;

import com.momocircle.domain.enums.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StokvelDtos {

    private StokvelDtos() {}

    public record CreateStokvelRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @NotNull StokvelType stokvelType,
            @NotBlank String currency,
            /** Fixed amount each member is expected to contribute every period. Required for ROTATIONAL. */
            @Positive BigDecimal contributionAmount,
            @NotNull RecurringFrequency frequency,
            LocalDate startDate,
            /** Optional shared target (creates the stokvel's primary goal). */
            @Positive BigDecimal targetAmount,
            LocalDate targetDate,
            ApprovalRuleType approvalRule,
            @Min(1) Integer approvalThreshold,
            Boolean membersCanVote
    ) {}

    public record UpdateSettingsRequest(
            ApprovalRuleType approvalRule,
            @Min(1) Integer approvalThreshold,
            Boolean membersCanVote,
            @Min(1) @Max(90) Integer requestValidityDays,
            /** Only accepted before any contribution or payout exists. */
            @Positive BigDecimal contributionAmount,
            RecurringFrequency frequency,
            LocalDate startDate
    ) {}

    public record StokvelContributeRequest(
            @NotNull @Positive BigDecimal amount,
            String idempotencyKey
    ) {}

    public record SetRotationOrderRequest(@NotEmpty List<String> userIds) {}

    public record RecordPayoutRequest(
            /** Guards double-submission: must equal the server's next cycle number. */
            @NotNull @Min(1) Integer expectedCycleNumber,
            @Size(max = 100) String reference
    ) {}

    public record CreateStokvelRequestRequest(
            @NotNull StokvelRequestType type,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(max = 500) String purpose,
            /** Named buyer (purchases) or beneficiary. Must be a member. */
            String buyerUserId,
            String goalId,
            String idempotencyKey
    ) {}

    public record VoteRequest(@Size(max = 300) String comment) {}

    public record LinkOrderRequest(@NotNull RelatedOrderKind kind, @NotBlank String orderId) {}

    public record RecordExecutionRequest(@NotBlank @Size(max = 200) String reference) {}

    // ---- responses ----

    public record StokvelSummary(
            String circleId, String name, StokvelType stokvelType, String currency,
            BigDecimal contributionAmount, RecurringFrequency frequency, CircleRole viewerRole, long memberCount) {}

    public record SettingsView(
            StokvelType stokvelType, BigDecimal contributionAmount, RecurringFrequency frequency, LocalDate startDate,
            LocalDate targetDate, String goalId, ApprovalRuleType approvalRule, Integer approvalThreshold,
            boolean membersCanVote, int requestValidityDays, boolean rotationLocked, Instant rotationLockedAt) {}

    public record StokvelOverview(
            String circleId, String name, String description, String currency, CircleRole viewerRole,
            SettingsView settings, long memberCount, int currentPeriod, LocalDate currentPeriodStart,
            LocalDate currentPeriodEnd, BigDecimal trackedContributions, BigDecimal trackedCommitted,
            BigDecimal trackedAvailable,
            /** Always states that PULA is not holding these funds. */
            String fundsNotice) {}

    public record MemberPeriodStatus(
            String userId, String fullName, int periodIndex, BigDecimal expected, BigDecimal paid, ContributionStatus status) {}

    public enum ContributionStatus { DUE, PARTIAL, PAID, MISSED }

    public record ContributionStatusView(
            int currentPeriod, LocalDate periodStart, LocalDate periodEnd, BigDecimal expectedPerMember,
            List<MemberPeriodStatus> currentPeriodMembers, List<MemberPeriodStatus> missed) {}

    public record GoalProgress(
            String goalId, BigDecimal target, BigDecimal current, BigDecimal remaining, int percent,
            LocalDate targetDate, List<MemberProgress> members) {}

    public record MemberProgress(String userId, String fullName, BigDecimal contributed) {}

    public record RotationSlotView(int position, String userId, String fullName, boolean paidOut, Integer paidInCycle) {}

    public record CycleView(
            int cycleNumber, int payoutPosition, String beneficiaryId, String beneficiaryName,
            BigDecimal expectedAmount, BigDecimal collectedAmount, String recordedByName, String reference, Instant paidAt) {}

    public record RotationView(
            boolean locked, Instant lockedAt, int completedCycles, int currentCycleNumber,
            String currentBeneficiaryId, String currentBeneficiaryName, String upcomingBeneficiaryId,
            String upcomingBeneficiaryName, BigDecimal currentCycleExpected, BigDecimal currentCycleCollected,
            List<RotationSlotView> order, List<CycleView> history, List<MemberPeriodStatus> currentCycleContributions) {}

    public record VoteView(String voterId, String voterName, VoteDecision decision, String comment, Instant at) {}

    public record RequestView(
            String id, String circleId, StokvelRequestType type, StokvelRequestStatus status, String requesterId,
            String requesterName, String buyerId, String buyerName, BigDecimal amount, String currency, String purpose,
            String goalId, ApprovalRuleType approvalRule, int requiredApprovals, int eligibleVoters, int approvals,
            int rejections, List<VoteView> votes, Instant expiresAt, Instant decidedAt, Instant executedAt,
            RelatedOrderKind relatedOrderKind, String relatedOrderId, String externalReference, Instant createdAt) {}

    public record InsightsView(
            String summary, String rotationExplanation, String goalForecast, List<String> scenarios,
            /** Reminder that the assistant only explains; people approve and pay. */
            String notice) {}
}
