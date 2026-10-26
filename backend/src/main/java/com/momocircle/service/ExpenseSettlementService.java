package com.momocircle.service;

import com.momocircle.domain.ExpenseShare;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ExpenseStatus;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.dto.ExpenseDtos.ConfirmRequest;
import com.momocircle.dto.ExpenseDtos.SettlementResponse;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.exception.ApiException;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.TxStatus;
import com.momocircle.repository.ExpenseShareRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.ExpenseService.ShareSnapshot;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.service.wallet.WalletIdentityService;
import com.momocircle.service.wallet.WalletService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.momocircle.dto.ExpenseDtos.SIMULATED_SETTLEMENT_NOTICE;

/**
 * Simulated settlement of split-bill shares through the Phase 9B wallet transfer service. This class NEVER touches a balance:
 * value moves only inside {@link WalletService#transfer}, which posts to the shared ledger. The share is marked PAID only after
 * that transfer has committed, and it then names the ledger transaction.
 *
 * <p>Safety, in the same style as the wallet payment-request service:
 * <ul>
 *   <li>only the participant themselves can pay their own share, with an explicit {@code confirm} and the amount they saw;</li>
 *   <li>the participant first CLAIMS the share with one guarded UPDATE (PENDING/ACCEPTED to PROCESSING, matching the amount),
 *       so two simultaneous attempts cannot both reach the ledger as different payments;</li>
 *   <li>the ledger idempotency key is derived from the share and its settle round ({@code expshare:<id>:r<n>}), so a retry,
 *       a double click or a crash recovery replays the same ledger transaction instead of posting twice;</li>
 *   <li>a claim is released only when the ledger definitively refused (nothing posted); if the outcome is unknown the claim
 *       stays and the participant's retry (or the recovery sweep) finishes it;</li>
 *   <li>no overdraft or credit is ever created: insufficient funds leaves the share unpaid.</li>
 * </ul>
 * Deliberately NOT {@code @Transactional}: the ledger owns its transactions and retries.
 */
@Service
public class ExpenseSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseSettlementService.class);
    private static final Set<LedgerException.Code> DEFINITIVE_REFUSALS = EnumSet.of(
            LedgerException.Code.INSUFFICIENT_FUNDS, LedgerException.Code.LIMIT_EXCEEDED, LedgerException.Code.INVALID_AMOUNT,
            LedgerException.Code.INVALID_CURRENCY, LedgerException.Code.CURRENCY_MISMATCH, LedgerException.Code.SELF_TRANSFER,
            LedgerException.Code.NOT_OWNER, LedgerException.Code.ACCOUNT_NOT_FOUND);

    private final ExpenseService expenses;
    private final ExpenseShareRepository shares;
    private final WalletService wallet;
    private final WalletIdentityService identity;
    private final LedgerTransactionRepository txRepo;
    private final LedgerOperationsService ledger;
    private final Duration staleProcessing;

    public ExpenseSettlementService(ExpenseService expenses, ExpenseShareRepository shares, WalletService wallet,
                                    WalletIdentityService identity, LedgerTransactionRepository txRepo, LedgerOperationsService ledger,
                                    @Value("${app.wallet.stale-processing-minutes:60}") int staleProcessingMinutes) {
        this.expenses = expenses;
        this.shares = shares;
        this.wallet = wallet;
        this.identity = identity;
        this.txRepo = txRepo;
        this.ledger = ledger;
        this.staleProcessing = Duration.ofMinutes(staleProcessingMinutes);
    }

    static String payKey(ShareSnapshot s) { return "expshare:" + s.shareId() + ":r" + s.settleRound(); }

    static String refundKey(ShareSnapshot s) { return "expshare:" + s.shareId() + ":refund:r" + s.settleRound(); }

    /** The ledger prefixes wallet transfer keys with "xfer:" (see WalletService.Ctx.keyScope). */
    private static String ledgerKey(String key) { return "xfer:" + key; }

    // ================================================================== participant pays their own share

    public SettlementResponse pay(User actor, String shareId, ConfirmRequest req) {
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw ApiException.badRequest("Explicit confirmation is required before demo funds are transferred");
        }
        return pay(actor, shareId, req, 0);
    }

    private SettlementResponse pay(User actor, String shareId, ConfirmRequest req, int depth) {
        ShareSnapshot s = expenses.snapshot(shareId, actor);
        if (!s.debtorId().equals(actor.getId())) throw ApiException.forbidden("Only the participant can pay their own share");
        if (s.expenseStatus() == ExpenseStatus.CANCELLED) throw ApiException.conflict("This expense was cancelled");
        if (s.payerId().equals(actor.getId())) throw ApiException.badRequest("You paid this expense, so you have nothing to pay");

        Money expected;
        try {
            expected = Money.ofDecimal(req.expectedAmount(), currencyOf(req, s));
        } catch (LedgerException e) {
            throw ApiException.badRequest("The amount you confirmed is not valid");
        }
        Money amount;
        try {
            amount = Money.ofDecimal(s.amount(), s.currency());
        } catch (LedgerException e) {
            throw ApiException.conflict("This share cannot be settled in the demo wallet");
        }
        if (expected.minor() != amount.minor() || !expected.currency().equals(amount.currency())) {
            throw ApiException.conflict("The amount changed since you reviewed it. Review the share again before paying.");
        }

        switch (s.status()) {
            case PAID -> {
                if (s.method() == SettlementMethod.LEDGER && actor.getId().equals(s.settledById())) return result(s, actor, true);
                throw ApiException.conflict("This share is already settled");
            }
            case PROCESSING -> {
                if (!actor.getId().equals(s.claimUserId())) throw ApiException.conflict("A payment for this share is being processed");
            }
            case PENDING, ACCEPTED -> {
                requireWalletReady(s);
                Instant now = Instant.now();
                int claimed = shares.claim(shareId, actor.getId(), EnumSet.of(ShareStatus.PENDING, ShareStatus.ACCEPTED),
                        ShareStatus.PROCESSING, s.amount(), now);
                if (claimed != 1) {
                    if (depth >= 1) throw ApiException.conflict("This share is being processed. Please check its status.");
                    return pay(actor, shareId, req, depth + 1); // lost a race or the amount changed: re-evaluate once
                }
            }
            case DISPUTED, DECLINED -> throw ApiException.conflict("You " + s.status().name().toLowerCase()
                    + " this share. Accept it first if you now want to pay it.");
            default -> throw ApiException.conflict("This share cannot be paid (it is " + s.status().name().toLowerCase() + ")");
        }
        requireWalletReadyOrRelease(s, actor);

        // From here this participant holds the claim and is the only one who can reach the ledger for this share.
        WalletService.Posted posted;
        try {
            posted = wallet.transfer(actor, identity.publicIdFor(s.payerId()), s.amount(), s.currency(), payKey(s));
        } catch (LedgerException | WalletException e) {
            boolean definitive = e instanceof WalletException || DEFINITIVE_REFUSALS.contains(((LedgerException) e).code());
            if (definitive) {
                shares.release(shareId, actor.getId(), ShareStatus.PROCESSING, ShareStatus.ACCEPTED, Instant.now()); // nothing posted
                safeRecordRefusal(shareId, actor, e instanceof LedgerException le ? le.code().name() : ((WalletException) e).code());
            }
            throw e; // otherwise the outcome may be unknown: the claim stays; the same participant's retry replays the same key
        }
        String txId = txIdOf(posted.view().reference());
        int marked = shares.markPaidByLedger(shareId, actor.getId(), txId, SettlementMethod.LEDGER, ShareStatus.PROCESSING,
                ShareStatus.PAID, Instant.now());
        if (marked != 1) {
            ShareSnapshot now = expenses.snapshotForRecovery(shareId);
            if (now.status() != ShareStatus.PAID) {
                log.error("Ledger posted {} but share {} could not be marked paid (status {}); retry or the sweep will finish it",
                        posted.view().reference(), shareId, now.status());
            }
        }
        expenses.onSettled(shareId, actor, posted.view().reference(), posted.replayed() || marked != 1);
        return new SettlementResponse(expenses.viewFor(s.expenseId(), actor), expenses.shareViewFor(shareId, actor), posted.view(),
                posted.replayed(), true, SIMULATED_SETTLEMENT_NOTICE);
    }

    private SettlementResponse result(ShareSnapshot s, User actor, boolean replayed) {
        TransactionView tx = null;
        if (s.ledgerTransactionId() != null) {
            tx = txRepo.findById(s.ledgerTransactionId()).map(t -> wallet.detail(actor, t.getProviderReference())).orElse(null);
        }
        return new SettlementResponse(expenses.viewFor(s.expenseId(), actor), expenses.shareViewFor(s.shareId(), actor), tx, replayed,
                true, SIMULATED_SETTLEMENT_NOTICE);
    }

    private void requireWalletReady(ShareSnapshot s) {
        try {
            wallet.currency(s.currency());
        } catch (WalletException e) {
            throw ApiException.conflict("The demo wallet only supports its default currency, so this share cannot be settled here");
        }
        if (!s.payerActive()) throw ApiException.conflict("The person who paid can no longer receive demo-wallet transfers");
    }

    private void requireWalletReadyOrRelease(ShareSnapshot s, User actor) {
        try {
            wallet.currency(s.currency());
        } catch (WalletException e) {
            shares.release(s.shareId(), actor.getId(), ShareStatus.PROCESSING, ShareStatus.ACCEPTED, Instant.now());
            throw ApiException.conflict("The demo wallet only supports its default currency, so this share cannot be settled here");
        }
    }

    private static String currencyOf(ConfirmRequest req, ShareSnapshot s) {
        return req.expectedCurrency() == null || req.expectedCurrency().isBlank() ? s.currency() : req.expectedCurrency().trim().toUpperCase();
    }

    private String txIdOf(String reference) {
        return txRepo.findByProviderReference(reference).map(LedgerTransactionEntity::getId)
                .orElseThrow(() -> new IllegalStateException("Ledger transaction " + reference + " not found after posting"));
    }

    private void safeRecordRefusal(String shareId, User actor, String code) {
        try {
            expenses.onPaymentRefused(shareId, actor, code);
        } catch (RuntimeException e) {
            log.warn("Could not record refused payment for share {}", shareId);
        }
    }

    // ================================================================== the payer returns a settled payment

    /**
     * A valid correction: the person who was paid confirms a compensating transfer of the same amount back to the participant.
     * It is a NEW ledger transaction (the original is never edited). If the payer no longer has the funds it is refused with
     * insufficient funds: PULA never recreates value that was already spent.
     */
    public SettlementResponse refund(User actor, String shareId, ConfirmRequest req) {
        if (!Boolean.TRUE.equals(req.confirm())) throw ApiException.badRequest("Explicit confirmation is required before demo funds are returned");
        ShareSnapshot s = expenses.snapshot(shareId, actor);
        if (!s.payerId().equals(actor.getId())) throw ApiException.forbidden("Only the person who was paid can refund this share");
        if (s.status() == ShareStatus.REFUNDED && actor.getId().equals(s.payerId())) {
            return new SettlementResponse(expenses.viewFor(s.expenseId(), actor), expenses.shareViewFor(shareId, actor), null, true, true,
                    SIMULATED_SETTLEMENT_NOTICE);
        }
        if (s.status() != ShareStatus.PAID || s.method() != SettlementMethod.LEDGER) {
            throw ApiException.conflict("Only a share settled through the demo wallet can be refunded");
        }
        Money amount = Money.ofDecimal(s.amount(), s.currency());
        if (req.expectedAmount() == null || Money.ofDecimal(req.expectedAmount(), currencyOf(req, s)).minor() != amount.minor()) {
            throw ApiException.conflict("The amount changed since you reviewed it. Review the share again before refunding.");
        }
        WalletService.Posted posted = wallet.transfer(actor, identity.publicIdFor(s.debtorId()), s.amount(), s.currency(), refundKey(s));
        String txId = txIdOf(posted.view().reference());
        int marked = shares.markUnsettled(shareId, txId, ShareStatus.REFUNDED, ShareStatus.PAID, SettlementMethod.LEDGER, Instant.now());
        if (marked == 1) expenses.onRefunded(shareId, actor, posted.view().reference(), false, req.note());
        return new SettlementResponse(expenses.viewFor(s.expenseId(), actor), expenses.shareViewFor(shareId, actor), posted.view(),
                posted.replayed(), true, SIMULATED_SETTLEMENT_NOTICE);
    }

    // ================================================================== operator reversal

    /**
     * Operator-only: reverses the share's ledger transaction using the ledger's own reversal. The ledger refuses it if the payer
     * has already spent the funds (no overdraft), in which case the share stays PAID.
     */
    public SettlementResponse operatorReverse(User admin, String shareId, String reason) {
        ShareSnapshot s = expenses.snapshotForRecovery(shareId);
        if (s.status() != ShareStatus.PAID || s.method() != SettlementMethod.LEDGER || s.ledgerTransactionId() == null) {
            throw ApiException.conflict("Only a share settled through the demo wallet can be reversed");
        }
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required");
        ledger.reverse(Actor.asAdmin(admin.getId()), s.ledgerTransactionId(), reason.trim());
        Optional<LedgerTransactionEntity> reversal = txRepo.findByInitiatorAndKey(admin.getId(), "reversal:" + s.ledgerTransactionId());
        String reversalId = reversal.map(LedgerTransactionEntity::getId).orElse(null);
        int marked = shares.markUnsettled(shareId, reversalId, ShareStatus.REVERSED, ShareStatus.PAID, SettlementMethod.LEDGER, Instant.now());
        if (marked == 1) {
            expenses.onRefunded(shareId, admin, reversal.map(LedgerTransactionEntity::getProviderReference).orElse("-"), true, reason);
        }
        // Do not expose participant expense details in the operator reversal response.
        return new SettlementResponse(
                null,
                null,
                null,
                marked != 1,
                true,
                SIMULATED_SETTLEMENT_NOTICE);
    }

    // ================================================================== recovery

    /** Finishes shares stuck in PROCESSING (a crash after the ledger commit) or releases them if nothing was posted. Repeat-safe. */
    public int sweep() {
        Instant now = Instant.now();
        int changed = 0;
        List<ExpenseShare> stale = shares.findStale(ShareStatus.PROCESSING, now.minus(staleProcessing), PageRequest.of(0, 200));
        for (ExpenseShare e : stale) {
            String key = ledgerKey("expshare:" + e.getId() + ":r" + e.getSettleRound());
            Optional<LedgerTransactionEntity> tx = txRepo.findByInitiatorAndKey(e.getClaimUserId(), key);
            if (tx.isPresent() && tx.get().getStatus() == TxStatus.POSTED) {
                changed += shares.markPaidByLedger(e.getId(), e.getClaimUserId(), tx.get().getId(), SettlementMethod.LEDGER,
                        ShareStatus.PROCESSING, ShareStatus.PAID, now);
            } else if (tx.isEmpty()) {
                changed += shares.release(e.getId(), e.getClaimUserId(), ShareStatus.PROCESSING, ShareStatus.ACCEPTED, now);
            }
        }
        return changed;
    }
}
