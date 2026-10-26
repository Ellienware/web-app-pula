package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.CreditEducationEntry;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.CalculateLoanRequest;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.dto.MoneyDtos.LoanCalculationResponse;
import com.momocircle.repository.CreditEducationEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this. Expected amortization figures
 * in these tests were independently computed with a small Python script
 * (M = P*r*(1+r)^n / ((1+r)^n - 1)) rather than derived from the service's
 * own code, so a bug in the service's formula would actually be caught
 * rather than the test just mirroring the implementation.
 */
class CreditEducationServiceTest {

    private CreditEducationEntryRepository repository;
    private IncomeRecordService incomeRecordService;
    private CreditEducationService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(CreditEducationEntryRepository.class);
        incomeRecordService = mock(IncomeRecordService.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        FinancialValidationService financialValidationService =
                new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));
        service = new CreditEducationService(repository, currencyProperties, incomeRecordService,
                financialValidationService);
        user = new User();
        user.setId("user-1");
        when(repository.save(any(CreditEducationEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(incomeRecordService.weeklySummary(user))
                .thenReturn(new IncomeSummaryResponse("ZAR", List.of(), null, null));
    }

    @Test
    void matchesIndependentlyComputedAmortizationForATypicalLoan() {
        // R1000 principal, 24% annual interest, 12-month term, no fees.
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("1000.00"), null, new BigDecimal("24.00"), 12, null);

        LoanCalculationResponse response = service.calculate(user, req);

        assertEquals(0, new BigDecimal("94.56").compareTo(response.monthlyPayment()));
        assertEquals(0, new BigDecimal("1134.72").compareTo(response.totalRepayment()));
        assertEquals(0, new BigDecimal("134.72").compareTo(response.totalInterest()));
        assertEquals(0, new BigDecimal("13.47").compareTo(response.totalCostPercent()));
    }

    @Test
    void zeroInterestLoanSplitsEvenlyWithNoInterestCharged() {
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("1200.00"), null, BigDecimal.ZERO, 12, null);

        LoanCalculationResponse response = service.calculate(user, req);

        assertEquals(0, new BigDecimal("100.00").compareTo(response.monthlyPayment()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(response.totalRepayment()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.totalInterest()));
        assertTrue(response.warnings().isEmpty());
    }

    @Test
    void flagsHighInterestRate() {
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("500.00"), null, new BigDecimal("120.00"), 6, null);

        LoanCalculationResponse response = service.calculate(user, req);

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("very high")));
    }

    @Test
    void flagsHighFeesRelativeToPrincipal() {
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("1000.00"), null, new BigDecimal("10.00"), 12, new BigDecimal("100.00"));

        LoanCalculationResponse response = service.calculate(user, req);

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("fees")));
    }

    @Test
    void flagsRepaymentCloseToTypicalMonthlyIncome() {
        when(incomeRecordService.weeklySummary(user)).thenReturn(new IncomeSummaryResponse(
                "ZAR", List.of(), new BigDecimal("500.00"), new BigDecimal("500.00")));
        // Typical monthly income ~= 500 * 4.33 = 2165. A big loan with a short term
        // pushes the monthly payment well above that.
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("20000.00"), null, new BigDecimal("20.00"), 6, null);

        LoanCalculationResponse response = service.calculate(user, req);

        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("monthly repayment")));
    }

    @Test
    void everyResponseCarriesTheDisclaimer() {
        CalculateLoanRequest req = new CalculateLoanRequest(
                new BigDecimal("500.00"), null, new BigDecimal("5.00"), 6, null);

        LoanCalculationResponse response = service.calculate(user, req);

        assertFalse(response.disclaimer() == null || response.disclaimer().isBlank());
        assertTrue(response.disclaimer().toLowerCase().contains("not a loan offer"));
    }
}
