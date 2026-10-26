package com.momocircle.service.wallet;

import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.domain.wallet.BusinessWalletRole.Permission;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Money;
import com.momocircle.payment.PaymentRails;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.BusinessService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * Pays a wholesale STOCK order from the SIMULATED PULA Money BUSINESS wallet of the buying business, to the supplier
 * business's simulated wallet. Domain rules stay in StockOrderService/StockPaymentService; this class only moves simulated
 * value through the shared ledger and then asks StockPaymentService to confirm from the ledger.
 *
 * <p>Rules: the buyer is the authenticated principal and must own the order's buying business; the debited wallet is always
 * that business's own (derived server-side, never supplied); the actor needs the SEND permission on it, so a business wallet
 * can never be charged for another business's order; the amount is the stored authoritative payment amount and the client's
 * amount is only compared with it. The ledger key is deterministic per payment, so a retry replays the same debit.
 * No real money moves and the supplier is NOT paid in the real world.
 */
@Service
public class StockWalletCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(StockWalletCheckoutService.class);

    public static final String NOTICE = "Simulated payment from your business's demo PULA Money wallet. No real money moved, "
            + "and the supplier has not been paid in the real world.";

    public record StockWalletPaymentResult(String orderId, PaymentStatus paymentStatus, StockOrderStatus orderStatus, boolean paid,
                                           boolean replayed, String ledgerReference, BigDecimal amount, String currency,
                                           boolean simulated, String notice) { }

    private record Snapshot(String orderId, String buyerBusinessId, String supplierBusinessId, boolean supplierActive,
                            String paymentId, String reference, BigDecimal amount, BigDecimal orderTotal, String currency,
                            PaymentStatus paymentStatus, StockOrderStatus orderStatus, boolean wallet, String ledgerTxId) { }

    private final StockOrderRepository stockOrders;
    private final PaymentRepository payments;
    private final BusinessService businessService;
    private final BusinessWalletAccessService access;
    private final LedgerAccountService accounts;
    private final LedgerOperationsService ledger;
    private final LedgerTransactionRepository txRepo;
    private final StockPaymentService confirmer;
    private final AuditLogService audit;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public StockWalletCheckoutService(StockOrderRepository stockOrders, PaymentRepository payments, BusinessService businessService,
                                      BusinessWalletAccessService access, LedgerAccountService accounts,
                                      LedgerOperationsService ledger, LedgerTransactionRepository txRepo,
                                      StockPaymentService confirmer, AuditLogService audit, PlatformTransactionManager tm) {
        this.stockOrders = stockOrders;
        this.payments = payments;
        this.businessService = businessService;
        this.access = access;
        this.accounts = accounts;
        this.ledger = ledger;
        this.txRepo = txRepo;
        this.confirmer = confirmer;
        this.audit = audit;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(tm);
    }

    public StockWalletPaymentResult pay(User buyer, String orderId, boolean confirm, BigDecimal expectedAmount) {
        if (!confirm) {
            throw WalletException.badRequest("CONFIRMATION_REQUIRED", "Please confirm the payment before demo funds are used.");
        }
        if (expectedAmount == null) {
            throw WalletException.badRequest("AMOUNT_REQUIRED", "The amount you are confirming is required.");
        }
        var buyerBusiness = businessService.requireOwn(buyer);
        Snapshot s = snapshot(orderId, buyerBusiness.getId());
        if (s == null) throw WalletException.notFound("ORDER_NOT_FOUND", "Stock order not found"); // also someone else's order
        if (access.findByBusinessId(buyer, s.buyerBusinessId()).filter(a -> a.allows(Permission.SEND)).isEmpty()) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "Your role on this business wallet does not allow payments");
        }
        if (!s.wallet()) {
            throw WalletException.conflict("NOT_WALLET_ORDER", "This order was not set up for wallet payment.");
        }
        if (s.paymentStatus() == PaymentStatus.SUCCESSFUL) {
            return result(s, true, null); // already paid: report it, charge nothing
        }
        if (s.paymentStatus() != PaymentStatus.PROCESSING || s.orderStatus() != StockOrderStatus.PAYMENT_PROCESSING) {
            throw WalletException.conflict("PAYMENT_CLOSED",
                    "This order can no longer be paid (it failed, expired or was cancelled). Please place it again.");
        }
        if (s.amount().compareTo(s.orderTotal()) != 0) {
            throw WalletException.conflict("AMOUNT_CHANGED", "The order total no longer matches this payment. Review the order again.");
        }
        if (expectedAmount.compareTo(s.amount()) != 0) {
            throw WalletException.conflict("AMOUNT_CHANGED", "The amount changed since you reviewed it. Review the order again before paying.");
        }
        if (!s.supplierActive()) {
            throw WalletException.conflict("SELLER_UNAVAILABLE", "This supplier is not accepting orders right now.");
        }
        Money money;
        try {
            money = Money.ofDecimal(s.amount(), s.currency());
        } catch (LedgerException e) {
            throw WalletException.conflict("AMOUNT_NOT_SUPPORTED", "This amount cannot be paid from the demo wallet.");
        }

        Account payer = accounts.getOrCreateBusinessWallet(s.buyerBusinessId(), s.currency());
        Account payee = accounts.getOrCreateBusinessWallet(s.supplierBusinessId(), s.currency());

        LedgerEngine.Result posted;
        try {
            posted = ledger.transfer(Actor.forBusiness(buyer.getId(), s.buyerBusinessId()), payer.id(), payee.id(), money,
                    PaymentRails.ledgerKey(s.paymentId()));
        } catch (LedgerException e) {
            auditSafely(buyer, "STOCK_WALLET_CHECKOUT_REFUSED", s.orderId(), "SIMULATED code=" + e.code());
            throw e; // nothing posted: the order is untouched
        }
        try {
            link(s.paymentId(), posted.tx().id());
        } catch (RuntimeException e) {
            log.warn("Stock wallet payment {} debited but not yet linked ({}); confirmation will adopt it by key", s.paymentId(), e.getClass().getSimpleName());
        }
        try {
            confirmer.confirmByReference(s.reference());
        } catch (RuntimeException e) {
            log.error("Stock wallet payment {} debited but confirmation failed ({}); the sweep will finish it", s.paymentId(), e.getClass().getSimpleName());
        }
        if (!posted.replayed()) {
            auditSafely(buyer, "STOCK_WALLET_CHECKOUT_DEBITED", s.orderId(), "SIMULATED ref=" + posted.tx().providerReference());
        }
        Snapshot after = snapshot(orderId, buyerBusiness.getId());
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

    private Snapshot snapshot(String orderId, String buyerBusinessId) {
        return readTx.execute(st -> stockOrders.findByIdAndBuyerBusinessId(orderId, buyerBusinessId).map(o -> {
            Payment p = o.getPayment();
            if (p == null) return null;
            return new Snapshot(o.getId(), o.getBuyerBusiness().getId(), o.getSupplierBusiness().getId(),
                    o.getSupplierBusiness().isActive(), p.getId(), p.getProviderReference(), p.getAmount(), o.getTotal(),
                    p.getCurrency(), p.getStatus(), o.getStatus(), PaymentRails.isWallet(p), p.getLedgerTransactionId());
        }).orElse(null));
    }

    private StockWalletPaymentResult result(Snapshot s, boolean replayed, String knownLedgerReference) {
        String ref = knownLedgerReference;
        if (ref == null && s.ledgerTxId() != null) {
            ref = txRepo.findById(s.ledgerTxId()).map(LedgerTransactionEntity::getProviderReference).orElse(null);
        }
        return new StockWalletPaymentResult(s.orderId(), s.paymentStatus(), s.orderStatus(), s.paymentStatus() == PaymentStatus.SUCCESSFUL,
                replayed, ref, s.amount(), s.currency(), true, NOTICE);
    }

    private void auditSafely(User actor, String action, String target, String details) {
        try {
            audit.record(actor, action, "StockOrder", target, details);
        } catch (RuntimeException e) {
            log.error("Audit entry for {} could not be written ({}); the payment result is unaffected", action, e.getClass().getSimpleName());
        }
    }
}
