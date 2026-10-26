package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.AffordabilityAssessment;
import com.momocircle.domain.Budget;
import com.momocircle.domain.BudgetCategory;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.AffordabilityResult;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.dto.MoneyDtos.AffordabilityAssessmentResponse;
import com.momocircle.dto.MoneyDtos.AssessAffordabilityRequest;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.repository.AffordabilityAssessmentRepository;
import com.momocircle.repository.BudgetRepository;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Implements the brief's Section 17 "Can I afford this?" - with one
 * important, deliberate limitation documented up front: this app has no
 * stored wallet/account balance anywhere (money moves through the external
 * MTN MoMo provider via Payment, never as an internally tracked ledger).
 * So this engine cannot and does not answer "do you have enough money right
 * now" - it answers a narrower, honestly-scoped question: "does this amount
 * fit the budget/income numbers you've told us about." Fabricating a
 * balance to answer the broader question would violate the brief's own
 * repeated instruction never to present invented figures as real. If the
 * user hasn't set up a budget or logged any income, the result is
 * INSUFFICIENT_INFORMATION, not a guess.
 *
 * Thresholds below are a simple, transparent heuristic - not a validated
 * financial model - and are documented as constants specifically so
 * they're easy to find, question, and tune, rather than buried as magic
 * numbers.
 */
@Service
public class AffordabilityService {

    /** Below this share of the relevant figure, the result is AFFORDABLE. */
    private static final BigDecimal AFFORDABLE_THRESHOLD = new BigDecimal("0.5");
    /** Below this share, CAUTION; at or above, NOT_RECOMMENDED. */
    private static final BigDecimal CAUTION_THRESHOLD = new BigDecimal("1.0");
    /** Tighter thresholds when comparing against overall weekly income rather than one budget line. */
    private static final BigDecimal INCOME_AFFORDABLE_THRESHOLD = new BigDecimal("0.3");
    private static final BigDecimal INCOME_CAUTION_THRESHOLD = new BigDecimal("0.7");

    private final AffordabilityAssessmentRepository affordabilityAssessmentRepository;
    private final BudgetRepository budgetRepository;
    private final IncomeRecordService incomeRecordService;
    private final CircleMembershipRepository circleMembershipRepository;
    private final CircleGoalRepository circleGoalRepository;
    private final CurrencyProperties currencyProperties;
    private final FinancialValidationService financialValidationService;

    public AffordabilityService(AffordabilityAssessmentRepository affordabilityAssessmentRepository,
                                 BudgetRepository budgetRepository,
                                 IncomeRecordService incomeRecordService,
                                 CircleMembershipRepository circleMembershipRepository,
                                 CircleGoalRepository circleGoalRepository,
                                 CurrencyProperties currencyProperties,
                                 FinancialValidationService financialValidationService) {
        this.affordabilityAssessmentRepository = affordabilityAssessmentRepository;
        this.budgetRepository = budgetRepository;
        this.incomeRecordService = incomeRecordService;
        this.circleMembershipRepository = circleMembershipRepository;
        this.circleGoalRepository = circleGoalRepository;
        this.currencyProperties = currencyProperties;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public AffordabilityAssessmentResponse assess(User user, AssessAffordabilityRequest req) {
        String currency = req.currency() == null || req.currency().isBlank()
                ? currencyProperties.getDefaultCurrency() : req.currency();
        financialValidationService.requireValidAmount(req.amount(), "amount");
        financialValidationService.requireValidCurrency(currency);

        Optional<Budget> budget = budgetRepository.findByUserId(user.getId());
        IncomeSummaryResponse incomeSummary = incomeRecordService.weeklySummary(user);
        boolean hasIncomeHistory = incomeSummary.averagePerBucket() != null;

        AffordabilityResult result;
        String reasoning;

        Optional<BudgetCategory> matchedCategory = budget.flatMap(b -> matchCategory(b, req.category()));

        if (matchedCategory.isPresent()) {
            BudgetCategory category = matchedCategory.get();
            BigDecimal ratio = ratio(req.amount(), category.getAllocatedAmount());
            result = classify(ratio, AFFORDABLE_THRESHOLD, CAUTION_THRESHOLD);
            reasoning = describeAgainstCategory(req.amount(), currency, category, ratio, result);
        } else if (hasIncomeHistory) {
            BigDecimal ratio = ratio(req.amount(), incomeSummary.averagePerBucket());
            result = classify(ratio, INCOME_AFFORDABLE_THRESHOLD, INCOME_CAUTION_THRESHOLD);
            reasoning = describeAgainstIncome(req.amount(), currency, incomeSummary, ratio, result);
        } else {
            result = AffordabilityResult.INSUFFICIENT_INFORMATION;
            reasoning = "You haven't set up a budget or logged any income yet, so this can't be checked "
                    + "against your own numbers. Add a budget category or a few income entries in Money "
                    + "Confidence and this check will get much more useful.";
        }

        String goalNote = activeGoalNote(user);
        if (goalNote != null) {
            reasoning = reasoning + " " + goalNote;
        }

        reasoning = reasoning + " This is an estimate based on what you've told us, not financial advice.";

        AffordabilityAssessment assessment = new AffordabilityAssessment();
        assessment.setUser(user);
        assessment.setAmount(req.amount());
        assessment.setCurrency(currency);
        assessment.setContext(req.context());
        assessment.setCategory(req.category());
        assessment.setResult(result);
        assessment.setReasoning(reasoning);
        return toResponse(affordabilityAssessmentRepository.save(assessment));
    }

    @Transactional(readOnly = true)
    public List<AffordabilityAssessmentResponse> history(User user) {
        return affordabilityAssessmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    private Optional<BudgetCategory> matchCategory(Budget budget, String requestedCategory) {
        if (requestedCategory == null || requestedCategory.isBlank()) return Optional.empty();
        String needle = requestedCategory.trim().toLowerCase(Locale.ROOT);
        return budget.getCategories().stream()
                .filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || needle.contains(c.getName().toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    private BigDecimal ratio(BigDecimal amount, BigDecimal against) {
        if (against == null || against.signum() == 0) {
            return new BigDecimal("999");
        }
        return amount.divide(against, 4, RoundingMode.HALF_UP);
    }

    private AffordabilityResult classify(BigDecimal ratio, BigDecimal affordableThreshold, BigDecimal cautionThreshold) {
        if (ratio.compareTo(affordableThreshold) <= 0) return AffordabilityResult.AFFORDABLE;
        if (ratio.compareTo(cautionThreshold) <= 0) return AffordabilityResult.CAUTION;
        return AffordabilityResult.NOT_RECOMMENDED;
    }

    private String describeAgainstCategory(BigDecimal amount, String currency, BudgetCategory category,
                                            BigDecimal ratio, AffordabilityResult result) {
        String pct = ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
        String base = currency + " " + amount + " is " + pct + " of your \"" + category.getName()
                + "\" budget of " + currency + " " + category.getAllocatedAmount() + ".";
        return switch (result) {
            case AFFORDABLE -> base + " That leaves good room in that category.";
            case CAUTION -> base + " That would use up most or all of that category.";
            case NOT_RECOMMENDED -> base + " That's more than you've budgeted for this category.";
            case INSUFFICIENT_INFORMATION -> base;
        };
    }

    private String describeAgainstIncome(BigDecimal amount, String currency, IncomeSummaryResponse summary,
                                          BigDecimal ratio, AffordabilityResult result) {
        String pct = ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
        String base = currency + " " + amount + " is about " + pct + " of your recent average weekly income ("
                + currency + " " + summary.averagePerBucket() + "). No matching budget category was found, "
                + "so this was compared to your overall income instead.";
        return switch (result) {
            case AFFORDABLE -> base + " That's a small share of a typical week.";
            case CAUTION -> base + " That's a significant share of a typical week's income.";
            case NOT_RECOMMENDED -> base + " That's close to or more than a typical week's income.";
            case INSUFFICIENT_INFORMATION -> base;
        };
    }

    /** One gentle sentence if the user has any active goal in any circle they belong to - never a number that isn't real. */
    private String activeGoalNote(User user) {
        List<CircleMembership> memberships = circleMembershipRepository.findByUserId(user.getId());
        if (memberships.isEmpty()) return null;
        List<String> circleIds = memberships.stream().map(m -> m.getCircle().getId()).toList();
        long activeGoals = circleGoalRepository.findByCircleIdInAndStatus(circleIds, GoalStatus.ACTIVE).size();
        if (activeGoals == 0) return null;
        return "You also have " + activeGoals + " active savings goal" + (activeGoals == 1 ? "" : "s")
                + " in your circles - worth keeping in mind.";
    }

    private AffordabilityAssessmentResponse toResponse(AffordabilityAssessment a) {
        return new AffordabilityAssessmentResponse(a.getId(), a.getAmount(), a.getCurrency(), a.getContext(),
                a.getCategory(), a.getResult(), a.getReasoning(), a.getCreatedAt());
    }
}
