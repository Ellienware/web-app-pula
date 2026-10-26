package com.momocircle.service.ledger;

import com.momocircle.domain.User;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Money;
import com.momocircle.repository.UserRepository;
import com.momocircle.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Entry point for simulated wallet operations: runs the {@link LedgerEngine} and records an entry in the existing
 * {@code audit_logs} table for every successful operation and for security-relevant denials (wrong owner, limit exceeded).
 *
 * <p>Deliberately NOT {@code @Transactional}: {@link JpaLedgerStore} owns the transaction (and its bounded retries), and
 * a surrounding transaction would disable the retries. The audit write is a separate short transaction after the ledger
 * has committed; if it fails it is logged at ERROR and the already-committed ledger result is still returned, because
 * failing the caller after funds moved would be worse. The immutable ledger rows (initiator, key, timestamps, reversal links)
 * remain the authoritative audit trail; {@code audit_logs} adds the operator-facing entry.
 *
 * <p>Every audit detail is prefixed {@code SIMULATED}. Replays of an idempotent request are not audited again.
 * The {@link Actor} MUST be built from the authenticated server-side principal, never from request data.
 */
@Service
public class LedgerOperationsService {

    private static final Logger log = LoggerFactory.getLogger(LedgerOperationsService.class);

    private final LedgerEngine engine;
    private final AuditLogService audit;
    private final UserRepository users;

    public LedgerOperationsService(LedgerEngine engine, AuditLogService audit, UserRepository users) {
        this.engine = engine;
        this.audit = audit;
        this.users = users;
    }

    public LedgerEngine.Result issueDemoValue(Actor actor, String walletId, Money amount, String idempotencyKey) {
        return run(actor, "LEDGER_DEMO_ISSUANCE", walletId, amount,
                () -> engine.issueDemoValue(actor, walletId, amount, idempotencyKey));
    }

    public LedgerEngine.Result transfer(Actor actor, String fromWalletId, String toWalletId, Money amount, String idempotencyKey) {
        return run(actor, "LEDGER_INTERNAL_TRANSFER", fromWalletId, amount,
                () -> engine.transfer(actor, fromWalletId, toWalletId, amount, idempotencyKey));
    }

    public LedgerEngine.Result requestWithdrawal(Actor actor, String walletId, Money amount, String idempotencyKey) {
        return run(actor, "LEDGER_WITHDRAWAL_REQUESTED", walletId, amount,
                () -> engine.requestWithdrawal(actor, walletId, amount, idempotencyKey));
    }

    public LedgerTransaction completeWithdrawal(Actor actor, String txId) {
        return runTx(actor, "LEDGER_WITHDRAWAL_COMPLETED", txId, "", () -> engine.completeWithdrawal(actor, txId));
    }

    public LedgerTransaction failWithdrawal(Actor actor, String txId, String reason) {
        return runTx(actor, "LEDGER_WITHDRAWAL_FAILED", txId, reason, () -> engine.failWithdrawal(actor, txId, reason));
    }

    public LedgerTransaction cancelWithdrawal(Actor actor, String txId) {
        return runTx(actor, "LEDGER_WITHDRAWAL_CANCELLED", txId, "", () -> engine.cancelWithdrawal(actor, txId));
    }

    /** Partial (or final-remainder) compensating refund of a wallet payment. Operator/system actors only. */
    public LedgerEngine.Result refundTransfer(Actor actor, String originalTxId, Money amount, String idempotencyKey) {
        return run(actor, "LEDGER_REFUND_TRANSFER", originalTxId, amount,
                () -> engine.refundTransfer(actor, originalTxId, amount, idempotencyKey));
    }

    public LedgerTransaction reverse(Actor actor, String txId, String reason) {
        return runTx(actor, "LEDGER_REVERSED", txId, reason, () -> engine.reverse(actor, txId, reason));
    }

    // ------------------------------------------------------------------

    private LedgerEngine.Result run(Actor actor, String action, String target, Money amount, Supplier<LedgerEngine.Result> op) {
        try {
            LedgerEngine.Result r = op.get();
            if (!r.replayed()) {
                record(actor, action, "LEDGER_TRANSACTION", r.tx().id(),
                        "SIMULATED " + amount.toDecimal() + " " + amount.currency() + " ref=" + r.tx().providerReference());
            }
            return r;
        } catch (LedgerException e) {
            recordDenial(actor, action, target, amount.toDecimal() + " " + amount.currency(), e);
            throw e;
        }
    }

    private LedgerTransaction runTx(Actor actor, String action, String txId, String reason, Supplier<LedgerTransaction> op) {
        try {
            LedgerTransaction tx = op.get();
            record(actor, action, "LEDGER_TRANSACTION", txId,
                    "SIMULATED status=" + tx.status() + (reason == null || reason.isBlank() ? "" : " reason=" + truncate(reason, 300)));
            return tx;
        } catch (LedgerException e) {
            recordDenial(actor, action, txId, "", e);
            throw e;
        }
    }

    private void recordDenial(Actor actor, String action, String target, String what, LedgerException e) {
        if (e.code() == Code.NOT_OWNER || e.code() == Code.LIMIT_EXCEEDED) {
            record(actor, action + "_DENIED", "LEDGER_ACCOUNT", target, "SIMULATED denied code=" + e.code() + " " + what);
        }
    }

    private void record(Actor actor, String action, String targetType, String targetId, String details) {
        try {
            User user = actor.system() ? null : users.getReferenceById(actor.userId());
            audit.record(user, action, targetType, targetId, truncate(details, 1000));
        } catch (RuntimeException ex) {
            log.error("Audit entry for {} could not be written ({}); the ledger result is unaffected", action,
                    ex.getClass().getSimpleName());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
