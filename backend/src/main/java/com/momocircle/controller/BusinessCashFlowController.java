package com.momocircle.controller;

import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.CashFlowDtos.BusinessExpenseEntryResponse;
import com.momocircle.dto.CashFlowDtos.BusinessIncomeEntryResponse;
import com.momocircle.dto.CashFlowDtos.RecordExpenseRequest;
import com.momocircle.dto.CashFlowDtos.RecordIncomeRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BusinessCashFlowService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * PULA Cash Flow — owner-only. All endpoints require the caller to own a
 * Business. Financial data for one business is never accessible to another.
 */
@RestController
public class BusinessCashFlowController {

    private final BusinessCashFlowService service;
    private final CurrentUserService currentUser;

    public BusinessCashFlowController(BusinessCashFlowService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /**
     * Main cash-flow summary for the caller's own business.
     * Defaults to the current calendar month if from/to are omitted.
     * Brief §16 output structure exactly.
     */
    @GetMapping("/api/cashflow/summary")
    public BusinessCashFlowSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(currentUser.require(), from, to);
    }

    // ---- Manual income entries ----

    @GetMapping("/api/cashflow/trend")
    public java.util.List<com.momocircle.dto.CashFlowDtos.CashFlowTrendPoint> trend(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "month") String granularity,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "6") int periods) {
        return service.trend(currentUser.require(), granularity, periods);
    }

    @PostMapping("/api/cashflow/income")
    public ResponseEntity<BusinessIncomeEntryResponse> recordIncome(@Valid @RequestBody RecordIncomeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordIncome(currentUser.require(), req));
    }

    @DeleteMapping("/api/cashflow/income/{id}")
    public ResponseEntity<Void> deleteIncome(@PathVariable String id) {
        service.deleteIncomeEntry(currentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    // ---- Manual expense entries ----

    @PostMapping("/api/cashflow/expenses")
    public ResponseEntity<BusinessExpenseEntryResponse> recordExpense(@Valid @RequestBody RecordExpenseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordExpense(currentUser.require(), req));
    }

    @DeleteMapping("/api/cashflow/expenses/{id}")
    public ResponseEntity<Void> deleteExpense(@PathVariable String id) {
        service.deleteExpenseEntry(currentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
}
