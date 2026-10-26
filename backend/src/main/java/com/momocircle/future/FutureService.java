package com.momocircle.future;

import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.future.FutureDtos.*;
import com.momocircle.repository.BusinessIncomeEntryRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.service.CircleService;
import com.momocircle.service.GoalService;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.config.CurrencyProperties;
import com.momocircle.stokvel.StokvelPermissions;
import com.momocircle.stokvel.StokvelSchedule;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.dto.CircleDtos;
import com.momocircle.dto.GoalDtos;
import com.momocircle.domain.enums.CircleType;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PULA Future orchestration. PLANNING AND SIMULATION ONLY.
 * This class intentionally has NO dependency on wallet, ledger, payment, payout, settlement or contribution-creation
 * services: it reads existing records and writes only allocation_* and projection_* tables.
 */
@Service
public class FutureService {

    private static final long MAX_MINOR = 100_000_000_000L; // R1bn planning ceiling
    private static final Map<PlanStatus, Set<PlanStatus>> TRANSITIONS = Map.of(
            PlanStatus.DRAFT, EnumSet.of(PlanStatus.ACTIVE, PlanStatus.ARCHIVED),
            PlanStatus.ACTIVE, EnumSet.of(PlanStatus.PAUSED, PlanStatus.ARCHIVED),
            PlanStatus.PAUSED, EnumSet.of(PlanStatus.ACTIVE, PlanStatus.ARCHIVED),
            PlanStatus.ARCHIVED, EnumSet.noneOf(PlanStatus.class));

    private final AllocationPlanRepository plans;
    private final AllocationPlanLineRepository planLines;
    private final AllocationCalculationRepository calcs;
    private final AllocationCalculationLineRepository calcLines;
    private final ProjectionScenarioRepository scenarios;
    private final CircleGoalRepository goals;
    private final CircleMembershipRepository memberships;
    private final ContributionRepository contributions;
    private final BusinessRepository businesses;
    private final BusinessIncomeEntryRepository businessIncome;
    private final CircleService circleService;
    private final GoalService goalService;
    private final BusinessPlanItemRepository planItems;
    private final RestaurantSettlementRepository settlements;
    private final StokvelProfileRepository stokvelProfiles;
    private final CurrencyProperties currency;

    public FutureService(AllocationPlanRepository plans, AllocationPlanLineRepository planLines,
                         AllocationCalculationRepository calcs, AllocationCalculationLineRepository calcLines,
                         ProjectionScenarioRepository scenarios, CircleGoalRepository goals,
                         CircleMembershipRepository memberships, ContributionRepository contributions,
                         BusinessRepository businesses, BusinessIncomeEntryRepository businessIncome,
                         CircleService circleService, GoalService goalService, BusinessPlanItemRepository planItems,
                         RestaurantSettlementRepository settlements, StokvelProfileRepository stokvelProfiles,
                         CurrencyProperties currency) {
        this.plans = plans; this.planLines = planLines; this.calcs = calcs; this.calcLines = calcLines;
        this.scenarios = scenarios; this.goals = goals; this.memberships = memberships;
        this.contributions = contributions; this.businesses = businesses; this.businessIncome = businessIncome;
        this.circleService = circleService;
        this.goalService = goalService; this.planItems = planItems; this.settlements = settlements;
        this.stokvelProfiles = stokvelProfiles; this.currency = currency;
    }

    // ------------------------------------------------------------------ money helpers
    static long toMinor(BigDecimal v, String field) {
        if (v == null) return 0L;
        if (v.signum() < 0) throw ApiException.badRequest("\"" + field + "\" must not be negative");
        if (v.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("\"" + field + "\" cannot have more than 2 decimal places");
        long minor;
        try { minor = v.movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw ApiException.badRequest("\"" + field + "\" is too large"); }
        if (minor > MAX_MINOR) throw ApiException.badRequest("\"" + field + "\" exceeds the planning maximum");
        return minor;
    }
    static BigDecimal fromMinor(long minor) { return BigDecimal.valueOf(minor, 2); }
    static int toBasisPoints(BigDecimal pct, String field) {
        if (pct == null) return 0;
        if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.badRequest("\"" + field + "\" must be between 0 and 100");
        if (pct.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("\"" + field + "\" cannot have more than 2 decimal places");
        return pct.movePointRight(2).intValueExact();
    }

    // ------------------------------------------------------------------ plans
    @Transactional
    public PlanResponse createPlan(User user, CreatePlanRequest req) {
        String businessId = null;
        if (req.businessId() != null && !req.businessId().isBlank()) {
            businessId = businesses.findByOwnerId(user.getId())
                    .filter(b -> b.getId().equals(req.businessId())).map(b -> b.getId())
                    .orElseThrow(() -> ApiException.forbidden("You can only plan for a business you own"));
        }
        AllocationPlan p = new AllocationPlan();
        p.setOwnerUserId(user.getId());
        p.setBusinessId(businessId);
        p.setName(req.name().trim());
        p.setStatus(PlanStatus.DRAFT);
        p.setReservesMinor(toMinor(req.reserves(), "reserves"));
        p.setCommitmentsMinor(toMinor(req.commitments(), "commitments"));
        p.setMinBufferMinor(toMinor(req.minOperatingBuffer(), "minOperatingBuffer"));
        if (businessId == null && (p.getReservesMinor() > 0 || p.getCommitmentsMinor() > 0 || p.getMinBufferMinor() > 0)) {
            // personal plans may declare expenses/reserves too; allowed. (No business restriction.)
        }
        p = plans.save(p);
        List<AllocationPlanLine> saved = new ArrayList<>();
        int pos = 0;
        for (PlanLineRequest lr : req.lines()) {
            AllocationPlanLine l = new AllocationPlanLine();
            l.setPlanId(p.getId());
            l.setPosition(pos++);
            l.setLabel(lr.label().trim());
            l.setLineType(lr.type());
            if (lr.type() == AllocationCalculator.LineType.FIXED) {
                l.setFixedMinor(toMinor(lr.fixedAmount(), "fixedAmount"));
            } else {
                l.setPercentBp(toBasisPoints(lr.percent(), "percent"));
            }
            if (lr.goalId() != null && !lr.goalId().isBlank()) {
                l.setGoalId(requireVisibleGoal(lr.goalId(), user).getId());
            }
            saved.add(planLines.save(l));
        }
        return toPlan(p, saved, List.of());
    }

    @Transactional(readOnly = true)
    public Page<PlanResponse> listPlans(User user, int page, int size) {
        return plans.findByOwnerUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), PlanStatus.ARCHIVED,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)))
                .map(p -> toPlan(p, planLines.findByPlanIdOrderByPositionAsc(p.getId()), planItems.findByPlanIdOrderByCreatedAtAsc(p.getId())));
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(User user, String planId) {
        AllocationPlan p = requirePlan(user, planId);
        return toPlan(p, planLines.findByPlanIdOrderByPositionAsc(p.getId()), planItems.findByPlanIdOrderByCreatedAtAsc(p.getId()));
    }

    /** Changes the planning status only. Never creates a payment, contribution, transfer or any financial record. */
    @Transactional
    public PlanResponse setStatus(User user, String planId, PlanStatus target) {
        AllocationPlan p = requirePlan(user, planId);
        if (!TRANSITIONS.get(p.getStatus()).contains(target)) {
            throw ApiException.conflict("A " + p.getStatus() + " plan cannot move to " + target);
        }
        List<AllocationPlanLine> lines = planLines.findByPlanIdOrderByPositionAsc(p.getId());
        if (target == PlanStatus.ACTIVE) {
            long bp = lines.stream().filter(l -> l.getLineType() == AllocationCalculator.LineType.PERCENT)
                    .mapToLong(AllocationPlanLine::getPercentBp).sum();
            if (lines.isEmpty() || bp > 10_000) {
                throw ApiException.badRequest("A plan needs at least one line and percentages of at most 100% to be active");
            }
        }
        p.setStatus(target);
        return toPlan(plans.save(p), lines, planItems.findByPlanIdOrderByCreatedAtAsc(p.getId()));
    }

    // ------------------------------------------------------------------ calculations (simulations)
    @Transactional
    public CalculationResponse calculate(User user, String planId, CalculateRequest req) {
        AllocationPlan p = requirePlan(user, planId);
        if (p.getStatus() == PlanStatus.PAUSED || p.getStatus() == PlanStatus.ARCHIVED) {
            throw ApiException.conflict("A " + p.getStatus() + " plan cannot be calculated; activate it first");
        }
        var existing = calcs.findByPlanIdAndIdempotencyKey(p.getId(), req.idempotencyKey());
        if (existing.isPresent()) {
            return toCalc(existing.get(), calcLines.findByCalculationIdOrderByPositionAsc(existing.get().getId()), true);
        }
        long basis; LocalDate asOf; List<String> incomeNotes = List.of();
        if (req.incomeSource() == IncomeSource.USER_ENTERED) {
            if (req.incomeAmount() == null) throw ApiException.badRequest("\"incomeAmount\" is required for USER_ENTERED income");
            basis = toMinor(req.incomeAmount(), "incomeAmount");
            asOf = LocalDate.now();
        } else {
            if (p.getBusinessId() == null) throw ApiException.badRequest("This plan is not linked to a business");
            if (req.from() == null || req.to() == null || req.to().isBefore(req.from()))
                throw ApiException.badRequest("A valid from/to period is required");
            if (req.incomeSource() == IncomeSource.BUSINESS_MANUAL_ENTRIES) {
                BigDecimal sum = businessIncome.sumByBusinessInPeriod(p.getBusinessId(), req.from(), req.to());
                basis = toMinor(sum == null ? BigDecimal.ZERO : sum, "income");
            } else {
                MerchantIncomeBasis.Result mb = settlementBasis(p.getBusinessId(), req.from(), req.to());
                basis = mb.basisMinor();
                incomeNotes = mb.notes();
            }
            asOf = req.to();
        }
        // Effective reserves/commitments = plan-level declared figures + business line items (planning inputs only).
        List<BusinessPlanItem> items = planItems.findByPlanIdOrderByCreatedAtAsc(p.getId());
        long reserves = p.getReservesMinor() + items.stream().filter(i -> i.getKind().isReserve()).mapToLong(BusinessPlanItem::getAmountMinor).sum();
        long commitments = p.getCommitmentsMinor() + items.stream().filter(i -> !i.getKind().isReserve()).mapToLong(BusinessPlanItem::getAmountMinor).sum();
        List<AllocationPlanLine> lines = planLines.findByPlanIdOrderByPositionAsc(p.getId());
        List<AllocationCalculator.Line> calcInput = lines.stream().map(l -> new AllocationCalculator.Line(
                l.getGoalId(), l.getLabel(), l.getLineType(), l.getFixedMinor(), l.getPercentBp())).toList();
        AllocationCalculator.Result r = AllocationCalculator.calculate(new AllocationCalculator.Input(
                basis, reserves, commitments, p.getMinBufferMinor(), calcInput));

        if (!r.valid()) {
            // Invalid (educational) scenario: shown with warnings but NEVER persisted as a plan result.
            return toCalcTransient(p, r, req.incomeSource(), asOf, incomeNotes);
        }
        AllocationCalculation c = new AllocationCalculation();
        c.setPlanId(p.getId()); c.setIdempotencyKey(req.idempotencyKey());
        c.setIncomeBasisMinor(r.incomeBasisMinor()); c.setIncomeSource(req.incomeSource()); c.setIncomeAsOf(asOf);
        c.setReservesMinor(r.reservesMinor()); c.setCommitmentsMinor(r.commitmentsMinor());
        c.setAllocatableMinor(r.allocatableMinor()); c.setTotalAllocatedMinor(r.totalAllocatedMinor());
        c.setRemainingMinor(r.remainingMinor()); c.setValid(true);
        c.setWarnings(r.warnings().isEmpty() ? null : String.join("\n", r.warnings()));
        c.setIncomeNotes(incomeNotes.isEmpty() ? null : String.join("\n", incomeNotes));
        c = calcs.save(c);
        List<AllocationCalculationLine> savedLines = new ArrayList<>();
        int pos = 0;
        for (AllocationCalculator.LineResult lr : r.lines()) {
            AllocationCalculationLine cl = new AllocationCalculationLine();
            cl.setCalculationId(c.getId()); cl.setGoalId(lr.goalId()); cl.setLabel(lr.label());
            cl.setAllocatedMinor(lr.allocatedMinor()); cl.setPosition(pos++);
            savedLines.add(calcLines.save(cl));
        }
        return toCalc(c, savedLines, false);
    }

    @Transactional(readOnly = true)
    public Page<CalculationResponse> history(User user, String planId, int page, int size) {
        AllocationPlan p = requirePlan(user, planId);
        return calcs.findByPlanIdOrderByCreatedAtDesc(p.getId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)))
                .map(c -> toCalc(c, calcLines.findByCalculationIdOrderByPositionAsc(c.getId()), false));
    }

    // ------------------------------------------------------------------ simulator
    public SimulationResponse simulate(SimulateRequest in) {
        BigDecimal mid = in.annualReturnPct();
        BigDecimal low = mid.subtract(BigDecimal.valueOf(3)).max(BigDecimal.valueOf(-50));
        BigDecimal high = mid.add(BigDecimal.valueOf(3)).min(BigDecimal.valueOf(50));
        List<ProjectionResult> out = new ArrayList<>();
        out.add(runProjection("Conservative illustration", in, low));
        out.add(runProjection("Your assumption", in, mid));
        out.add(runProjection("Higher-return illustration", in, high));
        List<String> assumptions = List.of(
                "Monthly compounding; contributions are made at the end of each month.",
                "Net annual rate = illustrative return minus the fee assumption.",
                "Contribution growth (if any) steps up once a year.",
                "Inflation reduces the value shown 'after inflation'; it does not change the nominal figure.",
                "Conservative and higher illustrations are 3 percentage points either side of your assumption. They are not forecasts.",
                "Actual returns fluctuate and may be negative. Nothing here is guaranteed.");
        return new SimulationResponse(out, assumptions, FutureDtos.PROJECTION_NOTICE);
    }

    @Transactional
    public ScenarioResponse saveScenario(User user, SaveScenarioRequest req) {
        SimulateRequest in = req.inputs();
        ProjectionCalculator.Result r = compute(in, in.annualReturnPct());
        ProjectionScenario s = new ProjectionScenario();
        s.setOwnerUserId(user.getId()); s.setLabel(req.label().trim());
        s.setStartingMinor(toMinor(in.startingAmount(), "startingAmount"));
        s.setMonthlyMinor(toMinor(in.monthlyContribution(), "monthlyContribution"));
        s.setYears(in.years());
        s.setAnnualReturnPct(in.annualReturnPct());
        s.setAnnualInflationPct(nz(in.annualInflationPct())); s.setAnnualFeePct(nz(in.annualFeePct()));
        s.setContributionGrowthPct(nz(in.contributionGrowthPct()));
        s.setTotalContributionsMinor(r.totalContributionsMinor());
        s.setFutureValueMinor(r.futureValueMinor()); s.setFutureValueRealMinor(r.futureValueRealMinor());
        return toScenario(scenarios.save(s));
    }

    @Transactional(readOnly = true)
    public Page<ScenarioResponse> listScenarios(User user, int page, int size) {
        return scenarios.findByOwnerUserIdOrderByCreatedAtDesc(user.getId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50))).map(this::toScenario);
    }

    // ------------------------------------------------------------------ dashboard
    @Transactional(readOnly = true)
    public Dashboard dashboard(User user) {
        List<CircleMembership> mine = memberships.findByUserId(user.getId());
        List<String> circleIds = mine.stream().map(m -> m.getCircle().getId()).toList();
        List<DashboardGoal> out = new ArrayList<>();
        if (!circleIds.isEmpty()) {
            for (CircleGoal g : goals.findByCircleIdInOrderByCreatedAtDesc(circleIds)) {
                out.add(dashboardGoal(g, user));
            }
        }
        List<DashboardPlan> ps = plans.findByOwnerUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), PlanStatus.ARCHIVED,
                PageRequest.of(0, 20)).map(p -> new DashboardPlan(p.getId(), p.getName(), p.getStatus(), p.getBusinessId())).getContent();
        return new Dashboard(out, ps, FutureDtos.PLANNING_NOTICE, List.of(
                "Simulated balance — not real money.",
                "Contribution status reflects the existing PULA workflow; a successful simulated payment is not proof of real settlement.",
                "Reaching a target does not mean PULA holds the money, and nothing is purchased automatically."));
    }


    // ------------------------------------------------------------------ goal metadata & schedule
    private static final Set<String> CATEGORIES = Set.of("EMERGENCY_FUND", "EDUCATION", "HOUSING", "VEHICLE_TRANSPORT",
            "BUSINESS_EQUIPMENT", "BUSINESS_EXPANSION", "STOCK_INVENTORY", "RETIREMENT", "GENERAL_SAVINGS", "SAVE_TO_BUY", "OTHER");

    /** Sets descriptive metadata only. Uses the existing circle-admin rule; never touches target, status or contributions. */
    @Transactional
    public DashboardGoal updateGoalMeta(User user, String goalId, GoalMetaRequest req) {
        CircleGoal g = goals.findById(goalId).orElseThrow(() -> ApiException.notFound("Goal not found"));
        CircleMembership m;
        try { m = circleService.requireMembership(g.getCircle().getId(), user); }
        catch (ApiException e) { throw ApiException.notFound("Goal not found"); }
        circleService.requireAdmin(m);
        if (req.category() != null) {
            String c = req.category().trim().toUpperCase();
            if (!CATEGORIES.contains(c)) throw ApiException.badRequest("Unknown category");
            g.setCategory(c);
        }
        if (req.priority() != null) g.setPriority(req.priority());
        goals.save(g);
        return dashboardGoal(g, user);
    }

    @Transactional(readOnly = true)
    public ScheduleResponse schedule(User user, String goalId, ScheduleCalculator.Frequency freq) {
        CircleGoal g = goals.findById(goalId).orElseThrow(() -> ApiException.notFound("Goal not found"));
        if (memberships.findByCircleIdAndUserId(g.getCircle().getId(), user.getId()).isEmpty()) throw ApiException.notFound("Goal not found");
        BigDecimal recorded = nz(contributions.sumConfirmedAmountForGoal(g.getId(), PaymentStatus.SUCCESSFUL));
        ScheduleCalculator.Result r = ScheduleCalculator.estimate(toMinor(g.getTargetAmount(), "target"), toMinor(recorded, "recorded"),
                LocalDate.now(), g.getDeadline(), freq);
        boolean ready = g.getPurchaseVertical() != null && g.getStatus() == GoalStatus.REACHED;
        String note = g.getPurchaseVertical() == null ? null
                : ready ? "Target recorded as reached. Use the existing Save-to-Buy purchase confirmation to continue; nothing is purchased automatically."
                        : "Save-to-Buy purchase is only available through the existing workflow once the target is reached and confirmed.";
        return new ScheduleResponse(g.getId(), freq.name(), g.getTargetAmount(), recorded, fromMinor(r.remainingMinor()),
                r.periods() == 0 ? null : r.periods(), r.perPeriodMinor() == 0 ? null : fromMinor(r.perPeriodMinor()),
                ready, note, r.warnings(), FutureDtos.PLANNING_NOTICE);
    }

    private DashboardGoal dashboardGoal(CircleGoal g, User user) {
        BigDecimal group = nz(contributions.sumConfirmedAmountForGoal(g.getId(), PaymentStatus.SUCCESSFUL));
        BigDecimal own = nz(contributions.sumConfirmedAmountForGoalAndContributor(g.getId(), user.getId(), PaymentStatus.SUCCESSFUL));
        boolean personal = memberships.findByCircleId(g.getCircle().getId()).size() == 1;
        return new DashboardGoal(g.getId(), g.getCircle().getId(), g.getCircle().getName(),
                String.valueOf(g.getCircle().getType()), g.getTitle(), g.getCategory(), g.getPriority(),
                g.getStatus().name(), g.getDeadline(), g.getTargetAmount(), group,
                g.getTargetAmount().subtract(group).max(BigDecimal.ZERO), own,
                personal ? "PERSONAL" : "GROUP_TRACKED", g.getPurchaseVertical() != null,
                personal ? "Recorded progress via the existing PULA workflow."
                         : "Group-tracked total across all members — not your personal balance.");
    }

    // ------------------------------------------------------------------ education
    public EducationIndex education() {
        return new EducationIndex(EducationContent.lessons().stream()
                .map(l -> new LessonSummary(l.id(), l.title(), l.level(), l.summary())).toList(),
                EducationContent.glossary(), EducationContent.DISCLAIMER);
    }

    public LessonDetail lesson(String id) {
        return new LessonDetail(EducationContent.find(id).orElseThrow(() -> ApiException.notFound("Lesson not found")),
                EducationContent.DISCLAIMER);
    }


    // ------------------------------------------------------------------ merchant settlement basis
    private MerchantIncomeBasis.Result settlementBasis(String businessId, LocalDate from, LocalDate to) {
        java.time.Instant f = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        java.time.Instant t = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return MerchantIncomeBasis.of(new MerchantIncomeBasis.Input(
                toMinor(nz(settlements.futurePaidNet(businessId, f, t)), "net"),
                toMinor(nz(settlements.futurePaidGross(businessId, f, t)), "gross"),
                toMinor(nz(settlements.futurePaidRefunded(businessId, f, t)), "refunds"),
                toMinor(nz(settlements.futurePendingNet(businessId, f, t)), "pending"),
                (int) settlements.futurePaidCount(businessId, f, t), (int) settlements.futurePendingCount(businessId, f, t)));
    }

    /** Read-only preview of the settlement-based income so the user can see what a calculation would use. */
    @Transactional(readOnly = true)
    public IncomePreview settlementPreview(User user, String businessId, LocalDate from, LocalDate to) {
        requireOwnedBusiness(user, businessId);
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("A valid from/to period is required");
        MerchantIncomeBasis.Result r = settlementBasis(businessId, from, to);
        return new IncomePreview(fromMinor(r.basisMinor()), fromMinor(r.grossMinor()), fromMinor(r.refundedMinor()),
                fromMinor(r.feesAndCommissionMinor()), fromMinor(r.pendingExcludedMinor()), r.paidCount(), r.pendingCount(), r.notes());
    }

    // ------------------------------------------------------------------ businesses and plan items
    private void requireOwnedBusiness(User user, String businessId) {
        businesses.findByOwnerId(user.getId()).filter(b -> b.getId().equals(businessId))
                .orElseThrow(() -> ApiException.notFound("Business not found"));
    }

    @Transactional(readOnly = true)
    public List<BusinessSummary> myBusinesses(User user) {
        return businesses.findByOwnerId(user.getId()).map(b -> new BusinessSummary(b.getId(), b.getName())).stream().toList();
    }

    @Transactional
    public PlanResponse addItem(User user, String planId, PlanItemRequest req) {
        AllocationPlan p = requirePlan(user, planId);
        if (p.getBusinessId() == null) throw ApiException.badRequest("Reserve and commitment items are for business plans");
        if (p.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("An archived plan cannot be changed");
        if (planItems.findByPlanIdOrderByCreatedAtAsc(p.getId()).size() >= 30) throw ApiException.badRequest("A plan can have at most 30 items");
        BusinessPlanItem i = new BusinessPlanItem();
        i.setPlanId(p.getId()); i.setKind(req.kind()); i.setLabel(req.label().trim());
        i.setAmountMinor(toMinor(req.amount(), "amount"));
        planItems.save(i);
        return toPlan(p, planLines.findByPlanIdOrderByPositionAsc(p.getId()), planItems.findByPlanIdOrderByCreatedAtAsc(p.getId()));
    }

    @Transactional
    public PlanResponse removeItem(User user, String planId, String itemId) {
        AllocationPlan p = requirePlan(user, planId);
        if (p.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("An archived plan cannot be changed");
        BusinessPlanItem i = planItems.findById(itemId).filter(x -> x.getPlanId().equals(p.getId()))
                .orElseThrow(() -> ApiException.notFound("Item not found"));
        planItems.delete(i);
        return toPlan(p, planLines.findByPlanIdOrderByPositionAsc(p.getId()), planItems.findByPlanIdOrderByCreatedAtAsc(p.getId()));
    }

    // ------------------------------------------------------------------ goals: create / pause / resume
    /**
     * Creates a goal through the EXISTING circle and goal services (so validation, admin rules and activity events are unchanged).
     * Without a circleId a personal one-member circle is created. Repeating an identical request returns the existing goal
     * instead of creating a duplicate circle.
     */
    @Transactional
    public DashboardGoal createGoal(User user, CreateGoalInput in) {
        String category = null;
        if (in.category() != null && !in.category().isBlank()) {
            category = in.category().trim().toUpperCase();
            if (!CATEGORIES.contains(category)) throw ApiException.badRequest("Unknown category");
        }
        String circleId = in.circleId();
        if (circleId == null || circleId.isBlank()) {
            String name = "Personal: " + in.title().trim();
            for (CircleMembership m : memberships.findByUserId(user.getId())) {
                var c = m.getCircle();
                if (c.getOwner() != null && c.getOwner().getId().equals(user.getId()) && name.equals(c.getName())) {
                    for (CircleGoal g : goals.findByCircleIdOrderByCreatedAtDesc(c.getId())) {
                        if (g.getStatus() == GoalStatus.ACTIVE && g.getTitle().equals(in.title().trim())
                                && g.getTargetAmount().compareTo(in.targetAmount()) == 0) {
                            return dashboardGoal(g, user);
                        }
                    }
                }
            }
            var circle = circleService.create(user, new CircleDtos.CreateCircleRequest(name.length() > 255 ? name.substring(0, 255) : name,
                    in.description(), CircleType.SAVINGS_GOAL, currency.getDefaultCurrency(), in.targetAmount()));
            circleId = circle.id();
        }
        var created = goalService.create(circleId, user, new GoalDtos.CreateGoalRequest(in.title(), in.description(), in.targetAmount(), in.deadline()));
        CircleGoal g = goals.findById(created.id()).orElseThrow(() -> ApiException.notFound("Goal not found"));
        if (category != null) g.setCategory(category);
        if (in.priority() != null) g.setPriority(in.priority());
        goals.save(g);
        return dashboardGoal(g, user);
    }

    @Transactional
    public DashboardGoal pauseGoal(User user, String goalId) {
        CircleGoal g = visibleGoalOr404(user, goalId);
        goalService.pause(g.getCircle().getId(), goalId, user);
        return dashboardGoal(goals.findById(goalId).orElseThrow(), user);
    }

    @Transactional
    public DashboardGoal resumeGoal(User user, String goalId) {
        CircleGoal g = visibleGoalOr404(user, goalId);
        goalService.resume(g.getCircle().getId(), goalId, user);
        return dashboardGoal(goals.findById(goalId).orElseThrow(), user);
    }

    private CircleGoal visibleGoalOr404(User user, String goalId) {
        CircleGoal g = goals.findById(goalId).orElseThrow(() -> ApiException.notFound("Goal not found"));
        if (memberships.findByCircleIdAndUserId(g.getCircle().getId(), user.getId()).isEmpty()) throw ApiException.notFound("Goal not found");
        return g;
    }

    // ------------------------------------------------------------------ stokvel role-aware view
    /** Read-only. Uses the existing StokvelPermissions rules; shows the caller's own obligation and clearly separates group totals. */
    @Transactional(readOnly = true)
    public List<StokvelView> stokvels(User user) {
        List<StokvelView> out = new ArrayList<>();
        for (StokvelProfile sp : stokvelProfiles.findForUser(user.getId())) {
            var membership = memberships.findByCircleIdAndUserId(sp.getCircle().getId(), user.getId());
            if (membership.isEmpty()) continue;
            var role = membership.get().getRole();
            boolean votes = sp.isMembersCanVote();
            StokvelPermissionsView perms = new StokvelPermissionsView(
                    StokvelPermissions.allows(role, StokvelPermissions.Action.CREATE_REQUEST, votes),
                    StokvelPermissions.allows(role, StokvelPermissions.Action.VOTE_ON_REQUEST, votes),
                    StokvelPermissions.allows(role, StokvelPermissions.Action.RECORD_PAYOUT, votes),
                    StokvelPermissions.allows(role, StokvelPermissions.Action.UPDATE_SETTINGS, votes));
            LocalDate periodEnds = null;
            if (sp.getStartDate() != null && sp.getContributionFrequency() != null) {
                int idx = StokvelSchedule.periodIndex(sp.getStartDate(), sp.getContributionFrequency(), LocalDate.now());
                if (idx >= 0) periodEnds = StokvelSchedule.periodEndExclusive(sp.getStartDate(), sp.getContributionFrequency(), idx).minusDays(1);
            }
            CircleGoal goal = sp.getGoal();
            BigDecimal mine = null, group = null, target = null;
            if (goal != null) {
                group = nz(contributions.sumConfirmedAmountForGoal(goal.getId(), PaymentStatus.SUCCESSFUL));
                mine = nz(contributions.sumConfirmedAmountForGoalAndContributor(goal.getId(), user.getId(), PaymentStatus.SUCCESSFUL));
                target = goal.getTargetAmount();
            }
            out.add(new StokvelView(sp.getCircle().getId(), sp.getCircle().getName(), String.valueOf(sp.getStokvelType()),
                    String.valueOf(role), perms, sp.getContributionAmount(),
                    sp.getContributionFrequency() == null ? null : sp.getContributionFrequency().name(), periodEnds,
                    goal == null ? null : goal.getId(), mine, group, target, List.of(
                    "Your role decides what you can do; PULA Future never bypasses the stokvel's approval rules.",
                    "Group progress is the stokvel's total, not your personal balance.",
                    "Recorded contributions follow the existing PULA workflow. A successful simulated payment is not proof of real money.",
                    "Per-period paid/missed status and payouts are managed in the stokvel itself.")));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private ProjectionResult runProjection(String name, SimulateRequest in, BigDecimal ret) {
        ProjectionCalculator.Result r = compute(in, ret);
        return new ProjectionResult(name, ret, r.netAnnualRatePct(), fromMinor(r.totalContributionsMinor()),
                fromMinor(r.futureValueMinor()), fromMinor(r.growthMinor()), fromMinor(r.futureValueRealMinor()));
    }

    private ProjectionCalculator.Result compute(SimulateRequest in, BigDecimal ret) {
        try {
            return ProjectionCalculator.project(new ProjectionCalculator.Input(
                    toMinor(in.startingAmount(), "startingAmount"), toMinor(in.monthlyContribution(), "monthlyContribution"),
                    in.years(), ret, in.annualInflationPct(), in.annualFeePct(), in.contributionGrowthPct()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private AllocationPlan requirePlan(User user, String planId) {
        // Not-found for other people's plans: do not reveal existence.
        return plans.findByIdAndOwnerUserId(planId, user.getId()).orElseThrow(() -> ApiException.notFound("Plan not found"));
    }

    private CircleGoal requireVisibleGoal(String goalId, User user) {
        CircleGoal g = goals.findById(goalId).orElseThrow(() -> ApiException.notFound("Goal not found"));
        if (memberships.findByCircleIdAndUserId(g.getCircle().getId(), user.getId()).isEmpty()) {
            throw ApiException.notFound("Goal not found");
        }
        if (g.getStatus() == GoalStatus.CLOSED) throw ApiException.badRequest("This goal is closed");
        return g;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private PlanResponse toPlan(AllocationPlan p, List<AllocationPlanLine> lines, List<BusinessPlanItem> items) {
        long itemReserves = items.stream().filter(i -> i.getKind().isReserve()).mapToLong(BusinessPlanItem::getAmountMinor).sum();
        long itemCommitments = items.stream().filter(i -> !i.getKind().isReserve()).mapToLong(BusinessPlanItem::getAmountMinor).sum();
        return new PlanResponse(p.getId(), p.getName(), p.getBusinessId(), p.getStatus(), fromMinor(p.getReservesMinor()),
                fromMinor(p.getCommitmentsMinor()), fromMinor(p.getMinBufferMinor()),
                lines.stream().map(l -> new PlanLineResponse(l.getId(), l.getGoalId(), l.getLabel(), l.getLineType(),
                        l.getLineType() == AllocationCalculator.LineType.FIXED ? fromMinor(l.getFixedMinor()) : null,
                        l.getLineType() == AllocationCalculator.LineType.PERCENT ? BigDecimal.valueOf(l.getPercentBp(), 2) : null)).toList(),
                items.stream().map(i -> new PlanItemResponse(i.getId(), i.getKind(), i.getLabel(), fromMinor(i.getAmountMinor()),
                        i.getKind().isReserve() ? "RESERVE" : "COMMITMENT")).toList(),
                fromMinor(p.getReservesMinor() + itemReserves), fromMinor(p.getCommitmentsMinor() + itemCommitments),
                p.getCreatedAt(), FutureDtos.PLANNING_NOTICE);
    }

    private static String sourceNote(IncomeSource s) {
        return switch (s) {
            case USER_ENTERED -> "Income entered by you. Unverified; not confirmed settlement.";
            case BUSINESS_MANUAL_ENTRIES -> "Sum of income entries recorded manually for your business. Orders, payments, settlements and wallet credits are not included and nothing here is verified settlement.";
            case MERCHANT_SIMULATED_SETTLEMENTS -> "Net of your merchant settlements marked paid in PULA (simulated payout). Not confirmed by a bank. Pending settlements are excluded.";
        };
    }

    private CalculationResponse toCalc(AllocationCalculation c, List<AllocationCalculationLine> lines, boolean replay) {
        List<String> w = c.getWarnings() == null ? List.of() : List.of(c.getWarnings().split("\n"));
        List<String> notes = c.getIncomeNotes() == null ? List.of() : List.of(c.getIncomeNotes().split("\n"));
        return new CalculationResponse(c.getId(), c.getPlanId(), c.isValid(), true, true, c.getIncomeSource(),
                sourceNote(c.getIncomeSource()), notes, c.getIncomeAsOf(), fromMinor(c.getIncomeBasisMinor()),
                fromMinor(c.getReservesMinor()), fromMinor(c.getCommitmentsMinor()), fromMinor(c.getAllocatableMinor()),
                fromMinor(c.getTotalAllocatedMinor()), fromMinor(c.getRemainingMinor()),
                lines.stream().map(l -> new CalcLineResponse(l.getGoalId(), l.getLabel(), fromMinor(l.getAllocatedMinor()))).toList(),
                w, c.getCreatedAt(), FutureDtos.PLANNING_NOTICE);
    }

    private CalculationResponse toCalcTransient(AllocationPlan p, AllocationCalculator.Result r, IncomeSource src, LocalDate asOf, List<String> notes) {
        return new CalculationResponse(null, p.getId(), false, false, true, src, sourceNote(src), notes, asOf,
                fromMinor(r.incomeBasisMinor()), fromMinor(r.reservesMinor()), fromMinor(r.commitmentsMinor()),
                fromMinor(r.allocatableMinor()), fromMinor(r.totalAllocatedMinor()), fromMinor(r.remainingMinor()),
                r.lines().stream().map(l -> new CalcLineResponse(l.goalId(), l.label(), fromMinor(l.allocatedMinor()))).toList(),
                r.warnings(), null, FutureDtos.PLANNING_NOTICE);
    }

    private ScenarioResponse toScenario(ProjectionScenario s) {
        return new ScenarioResponse(s.getId(), s.getLabel(), new SimulateRequest(fromMinor(s.getStartingMinor()),
                fromMinor(s.getMonthlyMinor()), s.getYears(), s.getAnnualReturnPct(), s.getAnnualInflationPct(),
                s.getAnnualFeePct(), s.getContributionGrowthPct()), fromMinor(s.getTotalContributionsMinor()),
                fromMinor(s.getFutureValueMinor()), fromMinor(s.getFutureValueRealMinor()), s.getCreatedAt(), FutureDtos.PROJECTION_NOTICE);
    }
}
