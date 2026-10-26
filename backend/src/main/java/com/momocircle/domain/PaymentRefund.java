package com.momocircle.domain;

import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One refund operation (PULA -> customer). An order can have several (partial refunds); the
 * idempotency key is unique so a retry, webhook replay, restart or admin click can never create a
 * second refund for the same operation. Stores no card or bank details.
 */
@Entity
@Table(name = "payment_refunds")
public class PaymentRefund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** "paystack" or the legacy provider's name. */
    @Column(nullable = false, length = 40)
    private String provider;

    /** The original charge reference the refund is against. */
    @Column(nullable = false, length = 200)
    private String originalReference;

    /** Provider's own refund id (Paystack refund id); null until the provider has accepted the refund. */
    @Column(length = 200)
    private String providerRefundReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CancellationCause reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(length = 500)
    private String customerNote;

    @Column(length = 500)
    private String merchantNote;

    @Column(nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    /** Provider's raw status word (e.g. "processing"); safe to store, never credentials. */
    @Column(length = 40)
    private String providerStatus;

    @Column(length = 500)
    private String failureReason;

    /**
     * SIMULATED wallet refunds only: the compensating ledger transaction this refund posted (partial/cumulative refunds).
     * Null for provider refunds and for a sole full refund done by reversing the original (that reversal links to its
     * original through the ledger's own reverses_tx_id). Unique so one ledger entry can never back two refunds.
     */
    @Column(name = "ledger_transaction_id", length = 36)
    private String ledgerTransactionId;

    @Column(nullable = false)
    private int attempts = 0;

    private Instant processedAt;

    @Version
    private long version;

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getOriginalReference() { return originalReference; }
    public void setOriginalReference(String originalReference) { this.originalReference = originalReference; }
    public String getProviderRefundReference() { return providerRefundReference; }
    public void setProviderRefundReference(String r) { this.providerRefundReference = r; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public CancellationCause getReason() { return reason; }
    public void setReason(CancellationCause reason) { this.reason = reason; }
    public RefundStatus getStatus() { return status; }
    public String getCustomerNote() { return customerNote; }
    public void setCustomerNote(String customerNote) { this.customerNote = customerNote; }
    public String getMerchantNote() { return merchantNote; }
    public void setMerchantNote(String merchantNote) { this.merchantNote = merchantNote; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason == null || failureReason.length() <= 500 ? failureReason : failureReason.substring(0, 500);
    }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLedgerTransactionId() { return ledgerTransactionId; }
    public void setLedgerTransactionId(String ledgerTransactionId) { this.ledgerTransactionId = ledgerTransactionId; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    /** Moves the status along its legal transitions only; an illegal move is a programming error. */
    public void transitionTo(RefundStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal refund status transition: " + status + " -> " + next + " (refund " + getId() + ")");
        }
        this.status = next;
    }
}
