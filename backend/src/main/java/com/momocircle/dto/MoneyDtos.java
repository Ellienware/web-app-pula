package com.momocircle.dto;

import com.momocircle.domain.enums.IncomeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MoneyDtos {

    private MoneyDtos() {}

    // ---- Financial profile ----

    public record UpdateFinancialProfileRequest(
            @NotNull IncomeType incomeType,
            /** Optional — defaults to the deployment's default currency if omitted. */
            String currency
    ) {}

    public record FinancialProfileResponse(
            String id,
            String userId,
            IncomeType incomeType,
            String currency,
            Instant createdAt
    ) {}

    // ---- Income records ----

    public record CreateIncomeRecordRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            String currency,
            @NotNull LocalDate receivedDate,
            String source
    ) {}

    public record IncomeRecordResponse(
            String id,
            BigDecimal amount,
            String currency,
            LocalDate receivedDate,
            String source,
            Instant createdAt
    ) {}

    /**
     * One bucket of the irregular-income planner — a calendar week or month,
     * summed from whatever IncomeRecords actually fall in it. Never a
     * prediction: {@code isProjected} is only ever true for future buckets
     * that the caller explicitly asked to see, and even then this is a
     * simple recent-average projection, not a guarantee — see the brief's
     * own instruction (§16) to use language like "estimated"/"projected"
     * and never imply certainty.
     */
    public record IncomeBucket(
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal total,
            int recordCount,
            boolean isProjected
    ) {}

    public record IncomeSummaryResponse(
            String currency,
            List<IncomeBucket> buckets,
            /** Simple mean of buckets with recordCount > 0. Null if there's no income history yet. */
            BigDecimal averagePerBucket,
            /** Lowest total among buckets with recordCount > 0 — a conservative "don't plan above this" figure. */
            BigDecimal minimumObservedPerBucket
    ) {}

    // ---- Budget ----

    public record BudgetCategoryRequest(
            @NotBlank String name,
            @NotNull @DecimalMin(value = "0.00") BigDecimal allocatedAmount
    ) {}

    public record CreateOrUpdateBudgetRequest(
            @NotBlank String label,
            String currency,
            @NotNull @jakarta.validation.Valid List<BudgetCategoryRequest> categories
    ) {}

    public record BudgetCategoryResponse(
            String id,
            String name,
            BigDecimal allocatedAmount
    ) {}

    public record BudgetResponse(
            String id,
            String label,
            String currency,
            List<BudgetCategoryResponse> categories,
            BigDecimal totalAllocated,
            Instant createdAt
    ) {}

    // ---- Affordability ----

    public record AssessAffordabilityRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            String currency,
            /** What the user is checking, e.g. "New school shoes". Shown back in their history. */
            String context,
            /** Budget category name this would fall under, if the user knows it, e.g. "Groceries". */
            String category
    ) {}

    public record AffordabilityAssessmentResponse(
            String id,
            BigDecimal amount,
            String currency,
            String context,
            String category,
            com.momocircle.domain.enums.AffordabilityResult result,
            String reasoning,
            Instant createdAt
    ) {}

    // ---- Responsible credit (educational only — MoMoCircle is not a lender) ----

    public record CalculateLoanRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal principal,
            String currency,
            @NotNull @DecimalMin(value = "0.00") BigDecimal annualInterestRatePercent,
            @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(360) Integer termMonths,
            /** Flat once-off fees, e.g. an initiation fee. Optional — treated as 0 if omitted. */
            BigDecimal fees
    ) {}

    public record LoanCalculationResponse(
            String id,
            BigDecimal principal,
            String currency,
            BigDecimal annualInterestRatePercent,
            int termMonths,
            BigDecimal fees,
            BigDecimal monthlyPayment,
            BigDecimal totalRepayment,
            BigDecimal totalInterest,
            BigDecimal totalCostPercent,
            List<String> warnings,
            String disclaimer,
            Instant createdAt
    ) {}
}
