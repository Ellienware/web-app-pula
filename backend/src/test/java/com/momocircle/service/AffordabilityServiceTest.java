package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.AffordabilityAssessment;
import com.momocircle.domain.Budget;
import com.momocircle.domain.BudgetCategory;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.AffordabilityResult;
import com.momocircle.dto.MoneyDtos.AffordabilityAssessmentResponse;
import com.momocircle.dto.MoneyDtos.AssessAffordabilityRequest;
import com.momocircle.dto.MoneyDtos.IncomeBucket;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.repository.AffordabilityAssessmentRepository;
import com.momocircle.repository.BudgetRepository;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class AffordabilityServiceTest {

    private AffordabilityAssessmentRepository assessmentRepository;
    private BudgetRepository budgetRepository;
    private IncomeRecordService incomeRecordService;
    private CircleMembershipRepository circleMembershipRepository;
    private CircleGoalRepository circleGoalRepository;
    private AffordabilityService service;
    private User user;

    @BeforeEach
    void setUp() {
        assessmentRepository = mock(AffordabilityAssessmentRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        incomeRecordService = mock(IncomeRecordService.class);
        circleMembershipRepository = mock(CircleMembershipRepository.class);
        circleGoalRepository = mock(CircleGoalRepository.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        FinancialValidationService financialValidationService =
                new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));

        service = new AffordabilityService(assessmentRepository, budgetRepository, incomeRecordService,
                circleMembershipRepository, circleGoalRepository, currencyProperties, financialValidationService);

        user = new User();
        user.setId("user-1");
        when(assessmentRepository.save(any(AffordabilityAssessment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(circleMembershipRepository.findByUserId(user.getId())).thenReturn(List.of());
        when(incomeRecordService.weeklySummary(user))
                .thenReturn(new IncomeSummaryResponse("ZAR", List.of(), null, null));
    }

    private Budget budgetWithCategory(String name, String amount) {
        Budget budget = new Budget();
        budget.setUser(user);
        budget.setCurrency("ZAR");
        budget.setLabel("Monthly budget");
        BudgetCategory category = new BudgetCategory();
        category.setBudget(budget);
        category.setName(name);
        category.setAllocatedAmount(new BigDecimal(amount));
        budget.getCategories().add(category);
        return budget;
    }

    @Test
    void insufficientInformationWhenNoBudgetAndNoIncomeHistory() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("500"), null, "Shoes", null);
        AffordabilityAssessmentResponse response = service.assess(user, req);

        assertEquals(AffordabilityResult.INSUFFICIENT_INFORMATION, response.result());
    }

    @Test
    void affordableWhenWellUnderCategoryAllocation() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.of(budgetWithCategory("Groceries", "1000.00")));

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("200"), null, null, "Groceries");
        AffordabilityAssessmentResponse response = service.assess(user, req);

        assertEquals(AffordabilityResult.AFFORDABLE, response.result());
    }

    @Test
    void cautionWhenCloseToCategoryAllocation() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.of(budgetWithCategory("Groceries", "1000.00")));

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("800"), null, null, "Groceries");
        AffordabilityAssessmentResponse response = service.assess(user, req);

        assertEquals(AffordabilityResult.CAUTION, response.result());
    }

    @Test
    void notRecommendedWhenExceedingCategoryAllocation() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.of(budgetWithCategory("Groceries", "1000.00")));

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("1500"), null, null, "Groceries");
        AffordabilityAssessmentResponse response = service.assess(user, req);

        assertEquals(AffordabilityResult.NOT_RECOMMENDED, response.result());
    }

    @Test
    void categoryMatchIsCaseInsensitiveAndPartial() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.of(budgetWithCategory("Groceries & food", "1000.00")));

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("100"), null, null, "groceries");
        AffordabilityAssessmentResponse response = service.assess(user, req);

        assertEquals(AffordabilityResult.AFFORDABLE, response.result());
        // The stored category is the request's raw text ("groceries"), not the matched
        // budget category's name ("Groceries & food") — matching is used only to find
        // the right allocation to compare against, not to rewrite what the user typed.
        assertEquals("groceries", response.category());
    }

    @Test
    void fallsBackToIncomeComparisonWhenNoBudgetCategoryMatches() {
        when(budgetRepository.findByUserId(user.getId())).thenReturn(Optional.of(budgetWithCategory("Rent", "3000.00")));
        when(incomeRecordService.weeklySummary(user)).thenReturn(new IncomeSummaryResponse(
                "ZAR", List.of(new IncomeBucket(null, null, new BigDecimal("800"), 1, false)),
                new BigDecimal("800.00"), new BigDecimal("800.00")));

        AssessAffordabilityRequest req = new AssessAffordabilityRequest(new BigDecimal("100"), null, null, "Transport");
        AffordabilityAssessmentResponse response = service.assess(user, req);

        // 100 / 800 = 12.5% of average weekly income -> AFFORDABLE
        assertEquals(AffordabilityResult.AFFORDABLE, response.result());
    }
}
