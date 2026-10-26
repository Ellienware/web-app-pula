package com.momocircle.controller;

import com.momocircle.dto.MoneyDtos.AffordabilityAssessmentResponse;
import com.momocircle.dto.MoneyDtos.AssessAffordabilityRequest;
import com.momocircle.dto.MoneyDtos.BudgetResponse;
import com.momocircle.dto.MoneyDtos.CalculateLoanRequest;
import com.momocircle.dto.MoneyDtos.CreateIncomeRecordRequest;
import com.momocircle.dto.MoneyDtos.CreateOrUpdateBudgetRequest;
import com.momocircle.dto.MoneyDtos.FinancialProfileResponse;
import com.momocircle.dto.MoneyDtos.IncomeRecordResponse;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.dto.MoneyDtos.LoanCalculationResponse;
import com.momocircle.dto.MoneyDtos.UpdateFinancialProfileRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.AffordabilityService;
import com.momocircle.service.BudgetService;
import com.momocircle.service.CreditEducationService;
import com.momocircle.service.FinancialProfileService;
import com.momocircle.service.IncomeRecordService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every endpoint here is scoped to {@code currentUser.require()} — there is
 * no path parameter for whose data to read/write, deliberately, so there is
 * no id for a client to tamper with to reach another user's financial data
 * (the class of bug the ecosystem brief's §27/§21 flags as a priority for
 * this whole domain).
 */
@RestController
@RequestMapping("/api/money")
public class MoneyController {

    private final FinancialProfileService financialProfileService;
    private final IncomeRecordService incomeRecordService;
    private final BudgetService budgetService;
    private final AffordabilityService affordabilityService;
    private final CreditEducationService creditEducationService;
    private final CurrentUserService currentUser;

    public MoneyController(FinancialProfileService financialProfileService, IncomeRecordService incomeRecordService,
                            BudgetService budgetService, AffordabilityService affordabilityService,
                            CreditEducationService creditEducationService, CurrentUserService currentUser) {
        this.financialProfileService = financialProfileService;
        this.incomeRecordService = incomeRecordService;
        this.budgetService = budgetService;
        this.affordabilityService = affordabilityService;
        this.creditEducationService = creditEducationService;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    public FinancialProfileResponse getProfile() {
        return financialProfileService.getOrCreate(currentUser.require());
    }

    @PutMapping("/profile")
    public FinancialProfileResponse updateProfile(@Valid @RequestBody UpdateFinancialProfileRequest req) {
        return financialProfileService.update(currentUser.require(), req);
    }

    @GetMapping("/income")
    public List<IncomeRecordResponse> listIncome() {
        return incomeRecordService.list(currentUser.require());
    }

    @PostMapping("/income")
    public ResponseEntity<IncomeRecordResponse> createIncome(@Valid @RequestBody CreateIncomeRecordRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(incomeRecordService.create(currentUser.require(), req));
    }

    @DeleteMapping("/income/{recordId}")
    public ResponseEntity<Void> deleteIncome(@PathVariable String recordId) {
        incomeRecordService.delete(currentUser.require(), recordId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/income/summary")
    public IncomeSummaryResponse incomeSummary() {
        return incomeRecordService.weeklySummary(currentUser.require());
    }

    @GetMapping("/budget")
    public BudgetResponse getBudget() {
        return budgetService.get(currentUser.require());
    }

    @PutMapping("/budget")
    public BudgetResponse saveBudget(@Valid @RequestBody CreateOrUpdateBudgetRequest req) {
        return budgetService.createOrUpdate(currentUser.require(), req);
    }

    @PostMapping("/affordability")
    public ResponseEntity<AffordabilityAssessmentResponse> assessAffordability(
            @Valid @RequestBody AssessAffordabilityRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(affordabilityService.assess(currentUser.require(), req));
    }

    @GetMapping("/affordability")
    public List<AffordabilityAssessmentResponse> affordabilityHistory() {
        return affordabilityService.history(currentUser.require());
    }

    @PostMapping("/credit")
    public ResponseEntity<LoanCalculationResponse> calculateLoan(@Valid @RequestBody CalculateLoanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(creditEducationService.calculate(currentUser.require(), req));
    }

    @GetMapping("/credit")
    public List<LoanCalculationResponse> creditHistory() {
        return creditEducationService.history(currentUser.require());
    }
}
