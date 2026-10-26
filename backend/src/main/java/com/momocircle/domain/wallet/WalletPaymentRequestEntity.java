package com.momocircle.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A request for simulated demo value. Amount, currency, purpose, requester and expiry are immutable after creation
 * ({@code updatable = false}); status transitions happen only through the guarded single-statement updates in
 * {@code WalletPaymentRequestRepository}. Maps {@code wallet_payment_requests} (V42).
 */
@Entity
@Table(name = "wallet_payment_requests")
public class WalletPaymentRequestEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "public_ref", length = 32, nullable = false, updatable = false)
    private String publicRef;

    @Column(name = "requester_user_id", length = 36, nullable = false, updatable = false)
    private String requesterUserId;

    /** Set when the request was made on behalf of a business: the value then lands in that business's wallet. */
    @Column(name = "requester_business_id", length = 36, updatable = false)
    private String requesterBusinessId;

    @Column(name = "target_payer_user_id", length = 36, updatable = false)
    private String targetPayerUserId;

    @Column(name = "payer_user_id", length = 36)
    private String payerUserId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "purpose", length = 140, nullable = false, updatable = false)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private PaymentRequestStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "ledger_transaction_id", length = 36)
    private String ledgerTransactionId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletPaymentRequestEntity() {
        // JPA
    }

    public static WalletPaymentRequestEntity open(String id, String publicRef, String requesterUserId, String targetPayerUserId,
                                                  long amountMinor, String currency, String purpose, Instant expiresAt, Instant now) {
        return open(id, publicRef, requesterUserId, null, targetPayerUserId, amountMinor, currency, purpose, expiresAt, now);
    }

    public static WalletPaymentRequestEntity open(String id, String publicRef, String requesterUserId, String requesterBusinessId,
                                                  String targetPayerUserId, long amountMinor, String currency, String purpose,
                                                  Instant expiresAt, Instant now) {
        WalletPaymentRequestEntity e = new WalletPaymentRequestEntity();
        e.requesterBusinessId = requesterBusinessId;
        e.id = id;
        e.publicRef = publicRef;
        e.requesterUserId = requesterUserId;
        e.targetPayerUserId = targetPayerUserId;
        e.amountMinor = amountMinor;
        e.currency = currency;
        e.purpose = purpose;
        e.status = PaymentRequestStatus.OPEN;
        e.expiresAt = expiresAt;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public String getId() { return id; }
    public String getPublicRef() { return publicRef; }
    public String getRequesterUserId() { return requesterUserId; }
    public String getRequesterBusinessId() { return requesterBusinessId; }
    public String getTargetPayerUserId() { return targetPayerUserId; }
    public String getPayerUserId() { return payerUserId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getPurpose() { return purpose; }
    public PaymentRequestStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getLedgerTransactionId() { return ledgerTransactionId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
