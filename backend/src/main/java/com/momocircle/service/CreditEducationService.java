package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.CreditEducationEntry;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.CalculateLoanRequest;
import com.momocircle.dto.MoneyDtos.LoanCalculationResponse;
import com.momocircle.repository.CreditEducationEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the brief's §18 "Responsible Credit" — a purely educational
 * calculator. PULA is not a lender: nothing here issues, applies for,
 * or represents a real loan offer, and the brief is explicit (§18, §37)
 * that this must never encourage borrowing, only inform the decision.
 * Every result carries a disclaimer to that effect (see
 * {@link #DISCLAIMER}).
 *
 * <p>Uses the standard reducing-balance amortization formula:
 * {@code M = P * r * (1+r)^n / ((1+r)^n - 1)} where r is the monthly rate
 * and n the term in months (M = P / n when r = 0). The exponentiation is
 * done in {@code double} rather than BigDecimal — implementing a
 * BigDecimal-precision iterative root/power solver would be real
 * additional complexity for an already-approximate educational estimate;
 * the final figures are rounded to 2 decimal places before being shown or
 * stored, which is precision enough for "roughly what would this cost me,"
 * the question this calculator actually answers.
 */
@Service
public class CreditEducationService {

    static final String DISCLAIMER = "This is an educational estimate, not a loan offer or financial advice. "
            + "PULA does not lend money or arrange credit.";

    /** Annual rates above this are flagged — a common informal/microloan territory where costs escalate fast. */
    private static final BigDecimal HIGH_RATE_THRESHOLD_PERCENT = new BigDecimal("60");
    /** Fees above this share of the principal are flagged. */
    private static final BigDecimal HIGH_FEE_RATIO = new BigDecimal("0.05");
    /** If total repayment exceeds the principal by more than this share, flagged. */
    private static final BigDecimal HIGH_TOTAL_COST_RATIO = new BigDecimal("0.30");

    private final CreditEducationEntryRepository creditEducationEntryRepository;
    private final CurrencyProperties currencyProperties;
    private final IncomeRecordService incomeRecordService;
    private final FinancialValidationService financialValidationService;

    public CreditEducationService(CreditEducationEntryRepository creditEducationEntryRepository,
                                   CurrencyProperties currencyProperties,
                                   IncomeRecordService incomeRecordService,
                                   FinancialValidationService financialValidationService) {
        this.creditEducationEntryRepository = creditEducationEntryRepository;
        this.currencyProperties = currencyProperties;
        this.incomeRecordService = incomeRecordService;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public LoanCalculationResponse calculate(User user, CalculateLoanRequest req) {
        String currency = req.currency() == null || req.currency().isBlank()
                ? currencyProperties.getDefaultCurrency() : req.currency();
        financialValidationService.requireValidAmount(req.principal(), "principal");
        financialValidationService.requireValidCurrency(currency);

        BigDecimal fees = req.fees() == null ? BigDecimal.ZERO : req.fees();
        BigDecimal principal = req.principal();
        int termMonths = req.termMonths();
        BigDecimal annualRatePercent = req.annualInterestRatePercent();

        BigDecimal monthlyPayment = monthlyPayment(principal, annualRatePercent, termMonths);
        BigDecimal totalRepaymentPrincipalAndInterest = monthlyPayment.multiply(BigDecimal.valueOf(termMonths));
        BigDecimal totalRepayment = totalRepaymentPrincipalAndInterest.add(fees).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = totalRepaymentPrincipalAndInterest.subtract(principal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCostPercent = totalRepayment.subtract(principal)
                .divide(principal, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        List<String> warnings = buildWarnings(user, principal, annualRatePercent, fees, totalRepayment, monthlyPayment);

        CreditEducationEntry entry = new CreditEducationEntry();
        entry.setUser(user);
        entry.setPrincipal(principal);
        entry.setCurrency(currency);
        entry.setAnnualInterestRatePercent(annualRatePercent);
        entry.setTermMonths(termMonths);
        entry.setFees(fees);
        entry.setMonthlyPayment(monthlyPayment);
        entry.setTotalRepayment(totalRepayment);
        entry.setTotalInterest(totalInterest);
        entry.setTotalCostPercent(totalCostPercent);
        entry.setWarnings(warnings);

        return toResponse(creditEducationEntryRepository.save(entry));
    }

    @Transactional(readOnly = true)
    public List<LoanCalculationResponse> history(User user) {
        return creditEducationEntryRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    private BigDecimal monthlyPayment(BigDecimal principal, BigDecimal annualRatePercent, int termMonths) {
        double monthlyRate = annualRatePercent.doubleValue() / 100.0 / 12.0;
        double p = principal.doubleValue();
        double payment;
        if (monthlyRate == 0.0) {
            payment = p / termMonths;
        } else {
            double factor = Math.pow(1 + monthlyRate, termMonths);
            payment = p * monthlyRate * factor / (factor - 1);
        }
        return BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> buildWarnings(User user, BigDecimal principal, BigDecimal annualRatePercent,
                                        BigDecimal fees, BigDecimal totalRepayment, BigDecimal monthlyPayment) {
        List<String> warnings = new ArrayList<>();

        if (annualRatePercent.compareTo(HIGH_RATE_THRESHOLD_PERCENT) > 0) {
            warnings.add("This interest rate (" + annualRatePercent + "% a year) is very high — the cost of "
                    + "borrowing escalates quickly at rates like this.");
        }
        if (fees.signum() > 0 && principal.signum() > 0) {
            BigDecimal feeRatio = fees.divide(principal, 4, RoundingMode.HALF_UP);
            if (feeRatio.compareTo(HIGH_FEE_RATIO) > 0) {
                warnings.add("The fees make up more than " + HIGH_FEE_RATIO.multiply(BigDecimal.valueOf(100))
                        + "% of the loan amount, on top of interest.");
            }
        }
        BigDecimal extraCost = totalRepayment.subtract(principal);
        if (principal.signum() > 0 && extraCost.divide(principal, 4, RoundingMode.HALF_UP)
                .compareTo(HIGH_TOTAL_COST_RATIO) > 0) {
            warnings.add("You'd repay " + totalRepayment + " in total for a " + principal
                    + " loan — that's a lot more than what you borrowed.");
        }

        var incomeSummary = incomeRecordService.weeklySummary(user);
        if (incomeSummary.averagePerBucket() != null) {
            BigDecimal typicalMonthlyIncome = incomeSummary.averagePerBucket().multiply(new BigDecimal("4.33"));
            if (typicalMonthlyIncome.signum() > 0
                    && monthlyPayment.compareTo(typicalMonthlyIncome) >= 0) {
                warnings.add("The monthly repayment is close to or more than your typical monthly income "
                        + "based on what you've logged — that would be very tight.");
            }
        }

        return warnings;
    }

    private LoanCalculationResponse toResponse(CreditEducationEntry e) {
        return new LoanCalculationResponse(e.getId(), e.getPrincipal(), e.getCurrency(),
                e.getAnnualInterestRatePercent(), e.getTermMonths(), e.getFees(), e.getMonthlyPayment(),
                e.getTotalRepayment(), e.getTotalInterest(), e.getTotalCostPercent(), e.getWarnings(),
                DISCLAIMER, e.getCreatedAt());
    }
}
