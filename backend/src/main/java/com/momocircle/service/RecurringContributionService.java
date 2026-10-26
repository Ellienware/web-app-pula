package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.Contribution;
import com.momocircle.domain.RecurringDue;
import com.momocircle.domain.RecurringPeriod;
import com.momocircle.domain.RecurringPlan;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.RecurringDueStatus;
import com.momocircle.domain.enums.RecurringStatus;
import com.momocircle.dto.RecurringDtos.CreateRecurringRequest;
import com.momocircle.dto.RecurringDtos.RecurringDueResponse;
import com.momocircle.dto.RecurringDtos.RecurringPeriodResponse;
import com.momocircle.dto.RecurringDtos.RecurringPlanResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.RecurringDueRepository;
import com.momocircle.repository.RecurringPlanRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recurring contribution plans (e.g. a monthly stokvel). Each generated period
 * creates a per-member {@link RecurringDue}; members pay their own due when
 * they choose. The platform never auto-charges a wallet — the scheduler only
 * generates the next period and flags overdue dues as MISSED.
 */
@Service
public class RecurringContributionService {

    private final RecurringPlanRepository planRepository;
    private final RecurringDueRepository dueRepository;
    private final CircleService circleService;
    private final GoalService goalService;
    private final ContributionService contributionService;
    private final ActivityService activityService;
    private final FinancialValidationService financialValidationService;

    public RecurringContributionService(RecurringPlanRepository planRepository,
                                        RecurringDueRepository dueRepository,
                                        CircleService circleService,
                                        GoalService goalService,
                                        ContributionService contributionService,
                                        ActivityService activityService,
                                        FinancialValidationService financialValidationService) {
        this.planRepository = planRepository;
        this.dueRepository = dueRepository;
        this.circleService = circleService;
        this.goalService = goalService;
        this.contributionService = contributionService;
        this.activityService = activityService;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public RecurringPlanResponse create(String circleId, User actor, CreateRecurringRequest req) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        Circle circle = membership.getCircle();
        financialValidationService.requireValidAmount(req.amountPerMember(), "amountPerMember");

        CircleGoal goal = null;
        if (req.goalId() != null && !req.goalId().isBlank()) {
            goal = goalService.requireGoal(circleId, req.goalId());
        }

        LocalDate start = req.startDate() != null ? req.startDate() : LocalDate.now();

        RecurringPlan plan = new RecurringPlan();
        plan.setCircle(circle);
        plan.setGoal(goal);
        plan.setCreatedBy(actor);
        plan.setTitle(req.title().trim());
        plan.setAmountPerMember(req.amountPerMember());
        plan.setFrequency(req.frequency());
        plan.setAnchorDate(start);
        plan.setNextDueDate(start);
        plan.setNote(req.note());
        plan = planRepository.save(plan);

        activityService.record(circle, actor, ActivityType.RECURRING_CREATED,
                actor.getFullName() + " started recurring plan \"" + plan.getTitle() + "\" ("
                        + req.amountPerMember().toPlainString() + " " + circle.getCurrency()
                        + " / " + plan.getFrequency().name().toLowerCase(Locale.ENGLISH) + ")");

        boolean generateFirst = req.generateFirstPeriod() == null || req.generateFirstPeriod();
        if (generateFirst) {
            generatePeriod(plan, actor);
            plan = planRepository.save(plan);
        }

        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public List<RecurringPlanResponse> list(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        return planRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Manually generates the next period (admin-triggered). */
    @Transactional
    public RecurringPlanResponse generateNextPeriod(String circleId, String planId, User actor) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        RecurringPlan plan = requirePlan(circleId, planId);
        if (plan.getStatus() != RecurringStatus.ACTIVE) {
            throw ApiException.badRequest("Only active plans can generate a new period");
        }
        generatePeriod(plan, actor);
        plan = planRepository.save(plan);
        return toResponse(plan);
    }

    @Transactional
    public RecurringPlanResponse setStatus(String circleId, String planId, User actor, RecurringStatus status) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        RecurringPlan plan = requirePlan(circleId, planId);
        plan.setStatus(status);
        plan = planRepository.save(plan);
        if (status == RecurringStatus.CANCELLED) {
            activityService.record(plan.getCircle(), actor, ActivityType.RECURRING_CANCELLED,
                    actor.getFullName() + " cancelled recurring plan \"" + plan.getTitle() + "\"");
        }
        return toResponse(plan);
    }

    /**
     * A member pays their own outstanding due. Runs a real charge through the
     * payment provider and, on success, records a contribution and marks the
     * due paid.
     */
    @Transactional
    public RecurringPlanResponse payDue(String circleId, String dueId, User actor) {
        circleService.requireMembership(circleId, actor);

        RecurringDue due = dueRepository.findById(dueId)
                .orElseThrow(() -> ApiException.notFound("Due not found"));
        RecurringPlan plan = due.getPeriod().getPlan();
        if (!plan.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Due does not belong to this circle");
        }
        if (!due.getMember().getId().equals(actor.getId())) {
            throw ApiException.forbidden("You can only pay your own recurring due");
        }
        if (due.getStatus() == RecurringDueStatus.PAID) {
            return toResponse(plan);
        }

        String idempotencyKey = "recurring-due-" + due.getId();
        Contribution contribution = contributionService.charge(
                plan.getCircle(), actor, plan.getGoal(), due.getAmount(), idempotencyKey,
                "Recurring contribution: " + plan.getTitle());

        if (contribution.getPayment().getStatus() == PaymentStatus.SUCCESSFUL) {
            due.setStatus(RecurringDueStatus.PAID);
            due.setPaidAt(java.time.Instant.now());
            due.setPayment(contribution.getPayment());
            dueRepository.save(due);
        } else {
            throw ApiException.badRequest("Payment did not succeed: "
                    + contribution.getPayment().getFailureReason());
        }
        return toResponse(plan);
    }

    /**
     * Daily job: for every active plan whose next due date has arrived, generate
     * the upcoming period. Any still-pending dues from earlier periods that are
     * now overdue are flagged MISSED.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void generateDuePeriods() {
        LocalDate today = LocalDate.now();
        List<RecurringPlan> due = planRepository
                .findByStatusAndNextDueDateLessThanEqual(RecurringStatus.ACTIVE, today);
        for (RecurringPlan plan : due) {
            markOverdueMissed(plan, today);
            generatePeriod(plan, null);
            planRepository.save(plan);
        }
    }

    // --- helpers ---

    private void generatePeriod(RecurringPlan plan, User actor) {
        Circle circle = plan.getCircle();
        LocalDate dueDate = plan.getNextDueDate();

        RecurringPeriod period = new RecurringPeriod();
        period.setPlan(plan);
        period.setLabel(labelFor(dueDate));
        period.setDueDate(dueDate);

        for (CircleMembership m : circleServiceMembers(circle.getId())) {
            RecurringDue d = new RecurringDue();
            d.setPeriod(period);
            d.setMember(m.getUser());
            d.setAmount(plan.getAmountPerMember());
            period.getDues().add(d);
        }
        plan.getPeriods().add(period);
        plan.setNextDueDate(plan.getFrequency().advance(dueDate));

        activityService.record(circle, actor, ActivityType.RECURRING_EXECUTED,
                "Recurring plan \"" + plan.getTitle() + "\" opened the " + period.getLabel() + " cycle");
    }

    private void markOverdueMissed(RecurringPlan plan, LocalDate today) {
        for (RecurringPeriod period : plan.getPeriods()) {
            if (period.getDueDate().isBefore(today)) {
                for (RecurringDue d : period.getDues()) {
                    if (d.getStatus() == RecurringDueStatus.PENDING) {
                        d.setStatus(RecurringDueStatus.MISSED);
                    }
                }
            }
        }
    }

    private List<CircleMembership> circleServiceMembers(String circleId) {
        return circleService.membershipsOf(circleId);
    }

    private RecurringPlan requirePlan(String circleId, String planId) {
        RecurringPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("Recurring plan not found"));
        if (!plan.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Plan does not belong to this circle");
        }
        return plan;
    }

    private String labelFor(LocalDate date) {
        return date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getYear();
    }

    private RecurringPlanResponse toResponse(RecurringPlan plan) {
        List<RecurringPeriodResponse> periods = new ArrayList<>();
        BigDecimal totalCollected = BigDecimal.ZERO;

        for (RecurringPeriod period : plan.getPeriods()) {
            List<RecurringDueResponse> dues = new ArrayList<>();
            int paid = 0;
            for (RecurringDue d : period.getDues()) {
                if (d.getStatus() == RecurringDueStatus.PAID) {
                    paid++;
                    totalCollected = totalCollected.add(d.getAmount());
                }
                dues.add(new RecurringDueResponse(
                        d.getId(),
                        d.getMember().getId(),
                        d.getMember().getFullName(),
                        d.getAmount(),
                        d.getStatus(),
                        d.getPaidAt()));
            }
            periods.add(new RecurringPeriodResponse(
                    period.getId(),
                    period.getLabel(),
                    period.getDueDate(),
                    period.getDues().size(),
                    paid,
                    dues));
        }

        return new RecurringPlanResponse(
                plan.getId(),
                plan.getCircle().getId(),
                plan.getTitle(),
                plan.getAmountPerMember(),
                plan.getCircle().getCurrency(),
                plan.getFrequency(),
                plan.getStatus(),
                plan.getNextDueDate(),
                plan.getGoal() == null ? null : plan.getGoal().getId(),
                plan.getGoal() == null ? null : plan.getGoal().getTitle(),
                plan.getNote(),
                plan.getCreatedBy().getFullName(),
                totalCollected,
                periods,
                plan.getCreatedAt());
    }
}
