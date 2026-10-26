package com.momocircle.future;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FutureDtos {
    private FutureDtos() {}

    public static final String PLANNING_NOTICE = "Planning estimate — no money has been transferred.";
    public static final String PROJECTION_NOTICE = "Investment projection — not an actual investment. Returns fluctuate and may be negative.";

    // ---- plans ----
    public record PlanLineRequest(String goalId, @NotBlank @Size(max = 120) String label,
                                  @NotNull AllocationCalculator.LineType type,
                                  @DecimalMin("0") BigDecimal fixedAmount,
                                  @DecimalMin("0") @DecimalMax("100") BigDecimal percent) {}

    public record CreatePlanRequest(@NotBlank @Size(max = 120) String name, String businessId,
                                    @DecimalMin("0") BigDecimal reserves, @DecimalMin("0") BigDecimal commitments,
                                    @DecimalMin("0") BigDecimal minOperatingBuffer,
                                    @NotEmpty @Size(max = 20) List<@Valid PlanLineRequest> lines) {}

    public record SetStatusRequest(@NotNull PlanStatus status) {}

    public record PlanLineResponse(String id, String goalId, String label, AllocationCalculator.LineType type,
                                   BigDecimal fixedAmount, BigDecimal percent) {}

    public record PlanItemRequest(@NotNull PlanItemKind kind, @NotBlank @Size(max = 120) String label,
                                  @NotNull @DecimalMin("0") BigDecimal amount) {}

    public record PlanItemResponse(String id, PlanItemKind kind, String label, BigDecimal amount, String treatedAs) {}

    /** reserves/commitments are the plan-level figures; effective* add the line items (reserve kinds vs commitment kinds). */
    public record PlanResponse(String id, String name, String businessId, PlanStatus status, BigDecimal reserves,
                               BigDecimal commitments, BigDecimal minOperatingBuffer, List<PlanLineResponse> lines,
                               List<PlanItemResponse> items, BigDecimal effectiveReserves, BigDecimal effectiveCommitments,
                               Instant createdAt, String notice) {}

    // ---- calculations ----
    public record CalculateRequest(@NotBlank @Size(max = 80) String idempotencyKey, @NotNull IncomeSource incomeSource,
                                   @DecimalMin("0") BigDecimal incomeAmount, LocalDate from, LocalDate to) {}

    public record CalcLineResponse(String goalId, String label, BigDecimal amount) {}

    public record CalculationResponse(String id, String planId, boolean valid, boolean persisted, boolean simulated,
                                      IncomeSource incomeSource, String incomeSourceNote, List<String> incomeNotes, LocalDate incomeAsOf,
                                      BigDecimal incomeBasis, BigDecimal reserves, BigDecimal commitments,
                                      BigDecimal allocatable, BigDecimal totalAllocated, BigDecimal remaining,
                                      List<CalcLineResponse> lines, List<String> warnings, Instant createdAt, String notice) {}

    // ---- simulator ----
    public record SimulateRequest(@NotNull @DecimalMin("0") BigDecimal startingAmount,
                                  @NotNull @DecimalMin("0") BigDecimal monthlyContribution,
                                  @Min(1) @Max(60) int years, @NotNull BigDecimal annualReturnPct,
                                  BigDecimal annualInflationPct, BigDecimal annualFeePct, BigDecimal contributionGrowthPct) {}

    public record SaveScenarioRequest(@NotBlank @Size(max = 120) String label, @Valid @NotNull SimulateRequest inputs) {}

    public record ProjectionResult(String name, BigDecimal annualReturnPct, BigDecimal netAnnualRatePct,
                                   BigDecimal totalContributions, BigDecimal futureValue, BigDecimal growth,
                                   BigDecimal futureValueAfterInflation) {}

    public record SimulationResponse(List<ProjectionResult> scenarios, List<String> assumptions, String notice) {}

    public record ScenarioResponse(String id, String label, SimulateRequest inputs, BigDecimal totalContributions,
                                   BigDecimal futureValue, BigDecimal futureValueAfterInflation, Instant createdAt, String notice) {}

    // ---- dashboard ----
    public record DashboardGoal(String goalId, String circleId, String circleName, String circleType, String title,
                                String category, String priority, String status, LocalDate deadline,
                                BigDecimal targetAmount, BigDecimal recordedProgress, BigDecimal remaining,
                                BigDecimal myRecordedContributions, String scope, boolean saveToBuy,
                                String progressNote) {}

    public record DashboardPlan(String id, String name, PlanStatus status, String businessId) {}

    public record Dashboard(List<DashboardGoal> goals, List<DashboardPlan> plans, String notice, List<String> disclosures) {}

    // ---- goal metadata & schedule ----
    public record GoalMetaRequest(@Size(max = 40) String category, @Pattern(regexp = "LOW|MEDIUM|HIGH") String priority) {}

    public record ScheduleResponse(String goalId, String frequency, BigDecimal targetAmount, BigDecimal recordedProgress,
                                   BigDecimal remaining, Integer periods, BigDecimal suggestedPerPeriod,
                                   boolean readyForExistingPurchaseWorkflow, String purchaseNote,
                                   List<String> warnings, String notice) {}

    // ---- education ----
    public record LessonSummary(String id, String title, String level, String summary) {}
    public record EducationIndex(List<LessonSummary> lessons, List<EducationContent.Term> glossary, String disclaimer) {}
    public record LessonDetail(EducationContent.Lesson lesson, String disclaimer) {}

    // ---- businesses, goal creation, stokvel, reminders, coach ----
    public record BusinessSummary(String id, String name) {}

    public record IncomePreview(BigDecimal basis, BigDecimal gross, BigDecimal refunded, BigDecimal feesAndCommission,
                                BigDecimal pendingExcluded, int paidCount, int pendingCount, List<String> notes) {}

    public record CreateGoalInput(@NotBlank @Size(max = 255) String title, @Size(max = 1000) String description,
                                  @NotNull @DecimalMin("0.01") BigDecimal targetAmount, LocalDate deadline,
                                  @Size(max = 40) String category, @Pattern(regexp = "LOW|MEDIUM|HIGH") String priority,
                                  String circleId) {}

    public record StokvelPermissionsView(boolean canCreateRequest, boolean canVote, boolean canRecordPayout, boolean canUpdateSettings) {}

    public record StokvelView(String circleId, String name, String stokvelType, String myRole,
                              StokvelPermissionsView permissions, BigDecimal contributionAmount, String contributionFrequency,
                              LocalDate currentPeriodEnds, String goalId, BigDecimal myRecordedContributions,
                              BigDecimal groupRecordedProgress, BigDecimal groupTarget, List<String> notes) {}

    public record ReminderSetting(ReminderKind kind, boolean enabled, String description) {}
    public record ReminderSettings(List<ReminderSetting> settings, List<String> notes) {}
    public record ReminderUpdate(@NotNull ReminderKind kind, boolean enabled) {}
    public record ReminderRunResult(int sent) {}

    public record CoachRequest(@NotBlank @Size(max = 500) String question, String calculationId) {}
    public record CoachResponse(String answer, String source, String disclaimer) {}
}
