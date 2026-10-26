package com.momocircle.service.wallet;

import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.TxStatus;
import com.momocircle.payment.PaymentRails;
import com.momocircle.payment.WalletPaymentAudit;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pays a consumer order (PULA Market or PULA Food) from the SIMULATED PULA Money wallet.
 *
 * <p>This class never holds or computes a balance: value moves only inside {@link LedgerOperationsService#transfer}, on the
 * shared ledger. It also never decides that an order is paid. It posts the debit, then hands the payment to the same
 * {@link StockPaymentService#confirmByReference} path every other order uses, which verifies the ledger transaction
 * (status, buyer, currency, exact amount) before fulfilling anything. A failed or refused debit therefore can never leave an
 * order marked paid, and a paid order always names a POSTED ledger transaction.
 *
 * <p>The order, its price and its seller were fixed by the server when the order was created
 * ({@code ConsumerPaystackCheckoutService#startWallet}); the buyer only supplies the order id, an explicit confirmation and
 * the amount they saw. The amount is compared with the stored one and never used to charge.
 *
 * <p>Recovery model (the ledger and the business records commit separately, so this is a saga, not one transaction):
 * <ol>
 *   <li>The ledger idempotency key is {@link PaymentRails#ledgerKey} (one per payment), so a retry, a double tap or a
 *       concurrent request replays the same ledger transaction and cannot debit twice.</li>
 *   <li>If the debit commits but the process dies before the payment row is linked or the order confirmed, the shared
 *       confirmation path finds the transaction by that same key and completes the order on the next sweep or retry.</li>
 *   <li>If the debit is refused (for example insufficient balance) nothing posted; the order stays awaiting payment so the
 *       buyer can add demo funds and pay again, and the existing expiry sweep cancels it and releases stock if they do not.</li>
 * </ol>
 *
 * <p>Deliberately NOT {@code @Transactional}: the ledger owns its transactions and retries, and a surrounding transaction
 * would disable them. The simulated debit credits the seller's SIMULATED business wallet; it is not a payout and does not
 * settle anything in the real world.
 */
@Service
public class WalletCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(WalletCheckoutService.class);

    /** Optional (field-injected) so existing construction is unchanged; used only to make reconciliation partial-refund aware. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.PaymentRefundRepository refundRepo;

    public static final String NOTICE = "Simulated payment from your demo PULA Money wallet. No real money moved, and no real "
            + "shop, restaurant or supplier has been paid.";

    /** What the buyer is told. {@code paid} is true only when the backend has verified the ledger debit and fulfilled the order. */
    public record WalletPaymentResult(String orderId, PaymentStatus paymentStatus, OrderStatus orderStatus, boolean paid,
                                      boolean replayed, String ledgerReference, BigDecimal amount, String currency,
                                      boolean simulated, String notice) { }

    public record Discrepancy(String paymentId, String orderId, List<WalletPaymentAudit.Issue> issues) { }

    private record Snapshot(String orderId, String buyerId, String businessId, boolean sellerActive, String paymentId,
                            String providerReference, BigDecimal amount, String currency, PaymentStatus paymentStatus,
                            OrderStatus orderStatus, boolean wallet, String ledgerTxId) { }

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final LedgerAccountService accounts;
    private final LedgerOperationsService ledger;
    private final LedgerTransactionRepository txRepo;
    private final StockPaymentService confirmer;
    private final AuditLogService audit;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public WalletCheckoutService(OrderRepository orders, PaymentRepository payments, LedgerAccountService accounts,
                                 LedgerOperationsService ledger, LedgerTransactionRepository txRepo,
                                 StockPaymentService confirmer, AuditLogService audit, PlatformTransactionManager tm) {
        this.orders = orders;
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.txRepo = txRepo;
        this.confirmer = confirmer;
        this.audit = audit;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(tm);
    }

    // ================================================================== buyer pays their own order

    /**
     * @param buyer          the authenticated user (from the server-side principal, never from the request)
     * @param confirm        the buyer's explicit confirmation
     * @param expectedAmount the amount the buyer saw; must equal the stored authoritative amount
     */
    public WalletPaymentResult pay(User buyer, String orderId, boolean confirm, BigDecimal expectedAmount) {
        if (!confirm) {
            throw WalletException.badRequest("CONFIRMATION_REQUIRED", "Please confirm the payment before demo funds are used.");
        }
        if (expectedAmount == null) {
            throw WalletException.badRequest("AMOUNT_REQUIRED", "The amount you are confirming is required.");
        }
        Snapshot s = snapshot(orderId);
        // Someone else's order is reported exactly like a missing one.
        if (s == null || !s.buyerId().equals(buyer.getId())) {
            throw WalletException.notFound("ORDER_NOT_FOUND", "Order not found");
        }
        if (!s.wallet()) {
            throw WalletException.conflict("NOT_WALLET_ORDER", "This order was not set up for wallet payment.");
        }
        if (s.paymentStatus() == PaymentStatus.SUCCESSFUL) {
            return result(s, true, null); // already paid: report it, charge nothing
        }
        if (s.paymentStatus() != PaymentStatus.PROCESSING || s.orderStatus() != OrderStatus.PAYMENT_PROCESSING) {
            throw WalletException.conflict("PAYMENT_CLOSED",
                    "This order can no longer be paid (it failed, expired or was cancelled). Please place it again.");
        }
        if (expectedAmount.compareTo(s.amount()) != 0) {
            throw WalletException.conflict("AMOUNT_CHANGED", "The amount changed since you reviewed it. Review the order again before paying.");
        }
        if (!s.sellerActive()) {
            throw WalletException.conflict("SELLER_UNAVAILABLE", "This seller is not accepting orders right now.");
        }
        Money money;
        try {
            money = Money.ofDecimal(s.amount(), s.currency());
        } catch (LedgerException e) {
            throw WalletException.conflict("AMOUNT_NOT_SUPPORTED", "This amount cannot be paid from the demo wallet.");
        }

        Account payer = accounts.getOrCreateWallet(buyer.getId(), s.currency());
        Account payee = accounts.getOrCreateBusinessWallet(s.businessId(), s.currency());

        LedgerEngine.Result posted;
        try {
            posted = ledger.transfer(Actor.ofUser(buyer.getId()), payer.id(), payee.id(), money, PaymentRails.ledgerKey(s.paymentId()));
        } catch (LedgerException e) {
            // Nothing was posted (or the outcome is unknown and the same key will replay). The order is untouched.
            auditSafely(buyer, "WALLET_CHECKOUT_REFUSED", s.orderId(), "SIMULATED code=" + e.code());
            throw e;
        }

        // From here the debit exists. Nothing below may fail the caller: the confirmation path is idempotent and the sweep
        // finds the ledger transaction by its key, so anything not finished now is finished later.
        try {
            link(s.paymentId(), posted.tx().id());
        } catch (RuntimeException e) {
            log.warn("Wallet payment {} debited but not yet linked ({}); confirmation will adopt it by key", s.paymentId(), e.getClass().getSimpleName());
        }
        try {
            confirmer.confirmByReference(s.providerReference());
        } catch (RuntimeException e) {
            log.error("Wallet payment {} debited but confirmation failed ({}); the sweep will finish it", s.paymentId(), e.getClass().getSimpleName());
        }
        if (!posted.replayed()) {
            auditSafely(buyer, "WALLET_CHECKOUT_DEBITED", s.orderId(), "SIMULATED ref=" + posted.tx().providerReference());
        }
        Snapshot after = snapshot(orderId);
        return result(after == null ? s : after, posted.replayed(), posted.tx().providerReference());
    }

    private void link(String paymentId, String txId) {
        writeTx.executeWithoutResult(st -> {
            Payment p = payments.findById(paymentId).orElseThrow(() -> new IllegalStateException("Payment not found"));
            if (p.getLedgerTransactionId() == null) {
                p.setLedgerTransactionId(txId);
                payments.save(p);
            } else if (!p.getLedgerTransactionId().equals(txId)) {
                throw new IllegalStateException("Payment is linked to a different ledger transaction");
            }
        });
    }

    private Snapshot snapshot(String orderId) {
        return readTx.execute(st -> orders.findById(orderId).map(o -> {
            Payment p = o.getPayment();
            if (p == null) return null;
            return new Snapshot(o.getId(), o.getBuyer().getId(), o.getBusiness().getId(), o.getBusiness().isActive(), p.getId(),
                    p.getProviderReference(), p.getAmount(), p.getCurrency(), p.getStatus(), o.getStatus(),
                    PaymentRails.isWallet(p), p.getLedgerTransactionId());
        }).orElse(null));
    }

    private WalletPaymentResult result(Snapshot s, boolean replayed, String knownLedgerReference) {
        String ref = knownLedgerReference;
        if (ref == null && s.ledgerTxId() != null) {
            ref = txRepo.findById(s.ledgerTxId()).map(LedgerTransactionEntity::getProviderReference).orElse(null);
        }
        return new WalletPaymentResult(s.orderId(), s.paymentStatus(), s.orderStatus(), s.paymentStatus() == PaymentStatus.SUCCESSFUL,
                replayed, ref, s.amount(), s.currency(), true, NOTICE);
    }

    private void auditSafely(User actor, String action, String targetId, String details) {
        try {
            audit.record(actor, action, "ORDER", targetId, details);
        } catch (RuntimeException e) {
            log.error("Audit entry for {} could not be written ({}); the payment result is unaffected", action, e.getClass().getSimpleName());
        }
    }

    // ================================================================== reconciliation (report only, never repairs)

    /** Compares wallet payments, their ledger transactions and their orders; returns only the ones that disagree. */
    public List<Discrepancy> reconcile(int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        return readTx.execute(st -> {
            List<Discrepancy> out = new ArrayList<>();
            var page = payments.findByProvider(PaymentRails.WALLET, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
            for (Payment p : page.getContent()) {
                TxStatus ledgerStatus = p.getLedgerTransactionId() == null ? null
                        : txRepo.findById(p.getLedgerTransactionId()).map(LedgerTransactionEntity::getStatus).orElse(null);
                Order order = orders.findByPaymentId(p.getId()).orElse(null);
                var view = order == null ? WalletPaymentAudit.OrderView.CANCELLED : orderView(order.getStatus());
                boolean compensated = refundRepo != null && p.getAmount() != null
                        && refundRepo.sumCompensatedByTransferForPayment(p.getId()).signum() > 0;
                var issues = WalletPaymentAudit.check(p.getStatus(), ledgerStatus, view, compensated);
                if (!issues.isEmpty()) out.add(new Discrepancy(p.getId(), order == null ? null : order.getId(), issues));
            }
            return out;
        });
    }

    static WalletPaymentAudit.OrderView orderView(OrderStatus s) {
        return switch (s) {
            case PENDING_PAYMENT, PAYMENT_PROCESSING -> WalletPaymentAudit.OrderView.AWAITING_PAYMENT;
            case PAID, PROCESSING, READY, COMPLETED -> WalletPaymentAudit.OrderView.PAID;
            case REFUND_PENDING -> WalletPaymentAudit.OrderView.REFUND_IN_PROGRESS;
            case REFUNDED -> WalletPaymentAudit.OrderView.REFUNDED;
            case CANCELLED -> WalletPaymentAudit.OrderView.CANCELLED;
        };
    }
}
