package com.momocircle.dto;

import com.momocircle.domain.enums.GoalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class GoalDtos {

    private GoalDtos() {}

    public record CreateGoalRequest(
            @NotBlank String title,
            String description,
            @NotNull @Positive BigDecimal targetAmount,
            LocalDate deadline
    ) {}

    public record GoalResponse(
            String id,
            String circleId,
            String title,
            String description,
            BigDecimal targetAmount,
            BigDecimal raisedAmount,
            Integer progressPercent,
            LocalDate deadline,
            GoalStatus status,
            String createdByName,
            Instant createdAt
    ) {}
}
