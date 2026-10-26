package com.momocircle.risk;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RiskDtos {
    private RiskDtos() {}

    /** What the paying user may see: no score, no rule ids, no thresholds. */
    public record UserView(String assessmentId, String intentId, RiskType type, BigDecimal amount, String currency,
                           RiskDecision decision, RiskLevel level, String explanation, boolean stepUpRequired,
                           boolean stepUpConfirmed, ReviewStatus reviewStatus, boolean canProceed, Instant expiresAt) {}

    /** Administrators only. */
    public record AdminView(String assessmentId, String intentId, String userId, String businessId, RiskType type, BigDecimal amount,
                            String currency, int score, RiskLevel level, RiskDecision decision, List<String> ruleIds,
                            List<String> unavailableSignals, String explanation, String ruleSetVersion, String mode,
                            Instant expiresAt, boolean simulated, ReviewStatus reviewStatus, StepUpStatus stepUpStatus,
                            String reviewedBy, Instant reviewedAt, String reviewNote, Instant createdAt,
                            IntentStatus intentStatus, String paymentReference, List<EventView> events) {}

    public record EventView(String type, String actorUserId, String detail, Instant createdAt) {}

    public record StepUpRequest(@NotNull BigDecimal amount, @NotNull String currency) {}

    public record ReviewRequest(boolean approve, String note) {}
}
