package com.momocircle.service.wallet;

import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.ServiceBookingStatus;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Money;
import com.momocircle.payment.PaymentRails;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.ServiceBookingRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * Pays a completed PULA Services booking from the customer's SIMULATED PULA Money wallet to the provider business's simulated
 * wallet. Booking rules (ownership, "completed before paid", risk gate, Save-to-Buy) stay in ServiceBookingService.startWalletPayment;
 * this class only moves simulated value through the shared ledger, then asks StockPaymentService to confirm from the ledger.
 *
 * <p>Payment status is distinct from booking status: a successful payment does not change the booking's lifecycle state, and
 * the booking is never treated as paid before the ledger transfer verifies. The customer is the authenticated principal, the
 * debited wallet is theirs, the credited wallet is the booking's provider business, and the amount is the stored payment amount.
 * No real money moves and the provider is NOT paid in the real world.
 */
@Service
public class ServiceWalletCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(ServiceWalletCheckoutService.class);

    public static final String NOTICE = "Simulated payment from your demo PULA Money wallet. No real money moved, and the "
            + "service provider has not been paid in the real world.";

    public record ServiceWalletPaymentResult(String bookingId, PaymentStatus paymentStatus, ServiceBookingStatus bookingStatus,
                                             boolean paid, boolean replayed, String ledgerReference, BigDecimal amount,
                                             String currency, boolean simulated, String notice) { }

    private record Snapshot(String bookingId, String customerId, String providerBusinessId, boolean providerActive, String paymentId,
                            String reference, BigDecimal amount, BigDecimal bookingTotal, String currency, PaymentStatus paymentStatus,
                            ServiceBookingStatus bookingStatus, boolean wallet, String ledgerTxId) { }

    private final ServiceBookingRepository bookings;
    private final PaymentRepository payments;
    private final LedgerAccountService accounts;
    private final LedgerOperationsService ledger;
    private final LedgerTransactionRepository txRepo;
    private final StockPaymentService confirmer;
    private final AuditLogService audit;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public ServiceWalletCheckoutService(ServiceBookingRepository bookings, PaymentRepository payments, LedgerAccountService accounts,
                                        LedgerOperationsService ledger, LedgerTransactionRepository txRepo,
                                        StockPaymentService confirmer, AuditLogService audit, PlatformTransactionManager tm) {
        this.bookings = bookings;
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

    public ServiceWalletPaymentResult pay(User customer, String bookingId, boolean confirm, BigDecimal expectedAmount) {
        if (!confirm) {
            throw WalletException.badRequest("CONFIRMATION_REQUIRED", "Please confirm the payment before demo funds are used.");
        }
        if (expectedAmount == null) {
            throw WalletException.badRequest("AMOUNT_REQUIRED", "The amount you are confirming is required.");
        }
        Snapshot s = snapshot(bookingId);
        if (s == null || !s.customerId().equals(customer.getId())) { // someone else's booking looks exactly like a missing one
            throw WalletException.notFound("BOOKING_NOT_FOUND", "Booking not found");
        }
        if (!s.wallet()) {
            throw WalletException.conflict("NOT_WALLET_ORDER", "This booking was not set up for wallet payment.");
        }
        if (s.paymentStatus() == PaymentStatus.SUCCESSFUL) {
            return result(s, true, null); // already paid: report it, charge nothing
        }
        if (s.paymentStatus() != PaymentStatus.PROCESSING || s.bookingStatus() != ServiceBookingStatus.COMPLETED) {
            throw WalletException.conflict("PAYMENT_CLOSED",
                    "This booking can no longer be paid this way (the attempt failed or the booking changed). Please start payment again.");
        }
        if (s.amount().compareTo(s.bookingTotal()) != 0) {
            throw WalletException.conflict("AMOUNT_CHANGED", "The booking total no longer matches this payment. Review the booking again.");
        }
        if (expectedAmount.compareTo(s.amount()) != 0) {
            throw WalletException.conflict("AMOUNT_CHANGED", "The amount changed since you reviewed it. Review the booking again before paying.");
        }
        if (!s.providerActive()) {
            throw WalletException.conflict("SELLER_UNAVAILABLE", "This service provider is not available right now.");
        }
        Money money;
        try {
            money = Money.ofDecimal(s.amount(), s.currency());
        } catch (LedgerException e) {
            throw WalletException.conflict("AMOUNT_NOT_SUPPORTED", "This amount cannot be paid from the demo wallet.");
        }

        Account payer = accounts.getOrCreateWallet(customer.getId(), s.currency());
        Account payee = accounts.getOrCreateBusinessWallet(s.providerBusinessId(), s.currency());

        LedgerEngine.Result posted;
        try {
            posted = ledger.transfer(Actor.ofUser(customer.getId()), payer.id(), payee.id(), money, PaymentRails.ledgerKey(s.paymentId()));
        } catch (LedgerException e) {
            auditSafely(customer, "SERVICE_WALLET_CHECKOUT_REFUSED", s.bookingId(), "SIMULATED code=" + e.code());
            throw e; // nothing posted: the booking is untouched
        }
        try {
            link(s.paymentId(), posted.tx().id());
        } catch (RuntimeException e) {
            log.warn("Service wallet payment {} debited but not yet linked ({}); confirmation will adopt it by key", s.paymentId(), e.getClass().getSimpleName());
        }
        try {
            confirmer.confirmByReference(s.reference());
        } catch (RuntimeException e) {
            log.error("Service wallet payment {} debited but confirmation failed ({}); the sweep will finish it", s.paymentId(), e.getClass().getSimpleName());
        }
        if (!posted.replayed()) {
            auditSafely(customer, "SERVICE_WALLET_CHECKOUT_DEBITED", s.bookingId(), "SIMULATED ref=" + posted.tx().providerReference());
        }
        Snapshot after = snapshot(bookingId);
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

    private Snapshot snapshot(String bookingId) {
        return readTx.execute(st -> bookings.findById(bookingId).map(b -> {
            Payment p = b.getPayment();
            if (p == null) return null;
            return new Snapshot(b.getId(), b.getCustomer().getId(), b.getBusiness().getId(), b.getBusiness().isActive(), p.getId(),
                    p.getProviderReference(), p.getAmount(), b.getTotal(), p.getCurrency(), p.getStatus(), b.getStatus(),
                    PaymentRails.isWallet(p), p.getLedgerTransactionId());
        }).orElse(null));
    }

    private ServiceWalletPaymentResult result(Snapshot s, boolean replayed, String knownLedgerReference) {
        String ref = knownLedgerReference;
        if (ref == null && s.ledgerTxId() != null) {
            ref = txRepo.findById(s.ledgerTxId()).map(LedgerTransactionEntity::getProviderReference).orElse(null);
        }
        return new ServiceWalletPaymentResult(s.bookingId(), s.paymentStatus(), s.bookingStatus(), s.paymentStatus() == PaymentStatus.SUCCESSFUL,
                replayed, ref, s.amount(), s.currency(), true, NOTICE);
    }

    private void auditSafely(User actor, String action, String target, String details) {
        try {
            audit.record(actor, action, "SERVICE_BOOKING", target, details);
        } catch (RuntimeException e) {
            log.error("Audit entry for {} could not be written ({}); the payment result is unaffected", action, e.getClass().getSimpleName());
        }
    }
}
