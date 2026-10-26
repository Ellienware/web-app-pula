package com.momocircle.domain;

import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A single money movement handled by a PaymentProvider. Decoupled from the
 * business object (Contribution) so the same provider abstraction can later
 * back MoMoMarket orders, payouts, refunds, etc.
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** e.g. "sandbox", "mtn". */
    @Column(nullable = false)
    private String provider;

    /** Provider-side reference (transaction id / financialTransactionId). */
    @Column
    private String providerReference;

    /** Idempotency key so a retry cannot double-charge. */
    @Column(unique = true)
    private String idempotencyKey;

    @Column(length = 500)
    private String failureReason;

    /**
     * Phase 9A: link to the SIMULATED shared-ledger transaction that moved value for this payment. Null for every
     * historical payment (never backfilled) and for any payment not funded through the ledger.
     */
    @Column(name = "ledger_transaction_id", length = 36)
    private String ledgerTransactionId;

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public void setLedgerTransactionId(String ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    /**
     * The only sanctioned way to change a Payment's status. Enforces the
     * state machine below; same-state calls are treated as a no-op (a
     * provider re-reporting an unchanged PROCESSING status is not a
     * transition). Anything not explicitly legal throws.
     *
     * <pre>
     * PENDING    -> PROCESSING, FAILED
     * PROCESSING -> SUCCESSFUL, FAILED
     * SUCCESSFUL -> REFUNDED
     * FAILED     -> (terminal)
     * REFUNDED   -> (terminal)
     * </pre>
     */
    public void transitionTo(PaymentStatus next) {
        if (next == this.status) {
            return; // idempotent re-report, not a real transition
        }
        if (!isLegalTransition(this.status, next)) {
            throw new IllegalStateException(
                    "Illegal payment status transition: " + this.status + " -> " + next
                            + " (payment " + getId() + ")");
        }
        this.status = next;
    }

    private static boolean isLegalTransition(PaymentStatus from, PaymentStatus to) {
        return switch (from) {
            case PENDING -> to == PaymentStatus.PROCESSING || to == PaymentStatus.FAILED;
            case PROCESSING -> to == PaymentStatus.SUCCESSFUL || to == PaymentStatus.FAILED;
            case SUCCESSFUL -> to == PaymentStatus.REFUNDED;
            case FAILED, REFUNDED -> false;
        };
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
    /** Null for legacy rows (consumer orders, savings, taxi) created before purpose routing existed. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentPurpose purpose;

    @Column(length = 1000)
    private String checkoutUrl;

    public PaymentPurpose getPurpose() { return purpose; }
    public void setPurpose(PaymentPurpose purpose) { this.purpose = purpose; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }
}
