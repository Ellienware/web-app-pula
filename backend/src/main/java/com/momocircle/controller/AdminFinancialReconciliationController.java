package com.momocircle.controller;

import com.momocircle.finance.FinancialRecordReconciler.Finding;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.FinancialReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operators only. Reports discrepancies between payments, refunds, merchant settlements and ledger transactions of SIMULATED
 * wallet sales. Read-only: it never repairs, deletes or creates any financial record.
 */
@RestController
public class AdminFinancialReconciliationController {

    private final FinancialReconciliationService service;
    private final CurrentUserService currentUser;

    public AdminFinancialReconciliationController(FinancialReconciliationService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/admin/financial-records/reconcile")
    public List<Finding> reconcile(@RequestParam(defaultValue = "200") int limit) {
        currentUser.requireAdmin();
        return service.reconcileWalletSales(limit);
    }
}
