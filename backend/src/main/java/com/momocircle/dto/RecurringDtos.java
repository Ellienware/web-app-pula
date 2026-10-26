package com.momocircle.dto;

import com.momocircle.domain.enums.RecurringDueStatus;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.RecurringStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RecurringDtos {

    private RecurringDtos() {}

    public record CreateRecurringRequest(
            @NotBlank String title,
            @NotNull @Positive BigDecimal amountPerMember,
            @NotNull RecurringFrequency frequency,
            /** Optional first due date; defaults to today. */
            LocalDate startDate,
            /** Optional goal to earmark collected dues toward. */
            String goalId,
            String note,
            /** When true (default), a first period is generated immediately. */
            Boolean generateFirstPeriod
    ) {}

    public record RecurringDueResponse(
            String id,
            String memberId,
            String memberName,
            BigDecimal amount,
            RecurringDueStatus status,
            Instant paidAt
    ) {}

    public record RecurringPeriodResponse(
            String id,
            String label,
            LocalDate dueDate,
            int expectedCount,
            int paidCount,
            List<RecurringDueResponse> dues
    ) {}

    public record RecurringPlanResponse(
            String id,
            String circleId,
            String title,
            BigDecimal amountPerMember,
            String currency,
            RecurringFrequency frequency,
            RecurringStatus status,
            LocalDate nextDueDate,
            String goalId,
            String goalTitle,
            String note,
            String createdByName,
            BigDecimal totalCollected,
            List<RecurringPeriodResponse> periods,
            Instant createdAt
    ) {}
}
