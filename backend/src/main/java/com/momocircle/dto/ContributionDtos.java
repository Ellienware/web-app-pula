package com.momocircle.dto;

import com.momocircle.domain.enums.PaymentRequestStatus;
import com.momocircle.domain.enums.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class ContributionDtos {

    private ContributionDtos() {}

    public record ContributeRequest(
            @NotNull @Positive BigDecimal amount,
            String paymentRequestId,
            /** Optional goal to earmark this contribution toward. */
            String goalId,
            /** Optional client-supplied idempotency key; server generates one if absent. */
            String idempotencyKey
    ) {}

    public record ContributionResponse(
            String id,
            String circleId,
            String contributorId,
            String contributorName,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String providerReference,
            String goalId,
            String goalTitle,
            Instant createdAt
    ) {}

    public record CreatePaymentRequestRequest(
            @NotNull @Positive BigDecimal amountPerMember,
            String note,
            LocalDate dueDate
    ) {}

    public record PaymentRequestResponse(
            String id,
            String circleId,
            String requestedByName,
            BigDecimal amountPerMember,
            String note,
            LocalDate dueDate,
            PaymentRequestStatus status,
            Instant createdAt
    ) {}
}
