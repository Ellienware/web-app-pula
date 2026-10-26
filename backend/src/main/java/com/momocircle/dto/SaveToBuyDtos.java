package com.momocircle.dto;

import com.momocircle.domain.enums.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SaveToBuyDtos {

    private SaveToBuyDtos() {}

    public static final String NOTICE =
            "Tracked against your savings goal. PULA does not hold your savings; the purchase is paid for at the normal checkout.";

    public record DesignateGoalRequest(
            @NotNull SaveToBuyVertical vertical,
            @Size(max = 200) String purpose
    ) {}

    public record CreateSaveToBuyRequest(
            @NotBlank String goalId,
            SaveToBuyVertical vertical,
            @NotBlank @Size(max = 500) String purpose,
            /** What the purchase is expected to cost. Informational for personal goals; the checkout total is what is charged. */
            @NotNull @Positive BigDecimal requestedAmount,
            /** Stokvel only: the member who will pay at checkout. Personal requests are always bought by the owner. */
            String namedBuyerUserId,
            String idempotencyKey
    ) {}

    public record CompleteRequest(@NotBlank @Size(max = 200) String reference) {}

    public record EligibleGoal(
            String goalId, String goalTitle, String circleId, String circleName, SaveToBuyScope scope,
            SaveToBuyVertical vertical, String purpose, String currency, BigDecimal target, BigDecimal saved,
            BigDecimal remaining, int percent, boolean goalReached, LocalDate targetDate, boolean canCreateRequest,
            /** The viewer may designate this goal for a purchase (UI hint; the server re-checks). */
            boolean canDesignate) {}

    public record ApprovalProgress(int required, int approvals, int rejections, int eligibleVoters, String stokvelRequestId) {}

    public record SaveToBuyView(
            String id, SaveToBuyScope scope, SaveToBuyStatus status, SaveToBuyVertical vertical, String purpose,
            String goalId, String goalTitle, String circleId, String circleName, String currency,
            BigDecimal goalTarget, BigDecimal saved, boolean goalReached,
            BigDecimal requestedAmount,
            /** requested - saved, never negative. Shown when the goal does not cover the purchase. */
            BigDecimal shortfall,
            /** requested - goal target (may be negative). The "difference" the checkout will still have to cover. */
            BigDecimal differenceFromTarget,
            /** Goal reached AND saved covers the requested amount. */
            boolean fullyFunded,
            String namedBuyerId, String namedBuyerName, String ownerId, String ownerName,
            ApprovalProgress approval, Instant expiresAt,
            RelatedOrderKind orderKind, String orderId, BigDecimal purchasedAmount, Instant purchasedAt,
            String completionReference,
            Instant createdAt, String notice) {}

    public record InsightsView(String summary, String shortfallExplanation, String savingAdvice, List<String> suggestions, String notice) {}
}
