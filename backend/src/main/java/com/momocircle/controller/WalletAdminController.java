package com.momocircle.controller;

import com.momocircle.dto.WalletDtos.FailWithdrawalRequest;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.ReconciliationView;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.wallet.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only SIMULATED outcomes for demo withdrawals, plus a per-wallet reconciliation check. Platform ADMIN role is
 * required ({@code requireAdmin}). No real money moves: completing a withdrawal only finalises the simulated ledger state.
 */
@RestController
@RequestMapping("/api/money/wallet/admin")
public class WalletAdminController {

    private final CurrentUserService currentUser;
    private final WalletService wallet;

    public WalletAdminController(CurrentUserService currentUser, WalletService wallet) {
        this.currentUser = currentUser;
        this.wallet = wallet;
    }

    /** Pending simulated withdrawals awaiting an operator outcome, oldest first. */
    @GetMapping("/withdrawals")
    public PageView<TransactionView> pendingWithdrawals(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return wallet.pendingWithdrawals(page, size);
    }

    @PostMapping("/withdrawals/{reference}/complete")
    public TransactionView complete(@PathVariable String reference) {
        return wallet.completeWithdrawal(currentUser.requireAdmin(), reference);
    }

    @PostMapping("/withdrawals/{reference}/fail")
    public TransactionView fail(@PathVariable String reference, @Valid @RequestBody(required = false) FailWithdrawalRequest req) {
        return wallet.failWithdrawal(currentUser.requireAdmin(), reference, req == null ? null : req.reason());
    }

    /** {@code walletId} is the public wallet id (PW-...). */
    @GetMapping("/reconcile/{walletId}")
    public ReconciliationView reconcile(@PathVariable String walletId) {
        currentUser.requireAdmin();
        return wallet.reconcileWallet(walletId);
    }
}
