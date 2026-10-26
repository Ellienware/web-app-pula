package com.momocircle.domain.ledger;

import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent form of {@link LedgerTransaction}. Maps table {@code ledger_transactions} (V40). Only {@code status},
 * {@code failureReason} and {@code updatedAt} may change after insert; every other column is {@code updatable = false}.
 * {@code simulated} is always true (also enforced by CHECK ck_ledger_tx_simulated).
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 24, nullable = false, updatable = false)
    private TxType txType; // Java name avoids clashing with the HQL function type()

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TxStatus status;

    @Column(name = "initiator_user_id", length = 64, nullable = false, updatable = false)
    private String initiatorUserId;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "fingerprint", length = 64, nullable = false, updatable = false)
    private String fingerprint;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "reverses_tx_id", length = 36, updatable = false)
    private String reversesTxId;

    @Column(name = "provider_reference", length = 40, nullable = false, updatable = false)
    private String providerReference;

    @Column(name = "simulated", nullable = false, updatable = false)
    private boolean simulated = true;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerTransactionEntity() {
        // JPA
    }

    public static LedgerTransactionEntity from(LedgerTransaction tx) {
        LedgerTransactionEntity e = new LedgerTransactionEntity();
        e.id = tx.id();
        e.txType = tx.type();
        e.status = tx.status();
        e.initiatorUserId = tx.initiatorUserId();
        e.idempotencyKey = tx.idempotencyKey();
        e.fingerprint = tx.fingerprint();
        e.currency = tx.currency();
        e.reversesTxId = tx.reversesTxId();
        e.providerReference = tx.providerReference();
        e.simulated = true;
        e.failureReason = tx.failureReason();
        e.createdAt = tx.createdAt();
        e.updatedAt = tx.createdAt();
        return e;
    }

    public void changeStatus(TxStatus next, String reason, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new com.momocircle.ledger.LedgerException(com.momocircle.ledger.LedgerException.Code.ILLEGAL_STATE,
                    "Cannot move " + status + " to " + next);
        }
        this.status = next;
        if (reason != null) this.failureReason = reason;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public TxType getType() { return txType; }
    public TxStatus getStatus() { return status; }
    public String getInitiatorUserId() { return initiatorUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getFingerprint() { return fingerprint; }
    public String getCurrency() { return currency; }
    public String getReversesTxId() { return reversesTxId; }
    public String getProviderReference() { return providerReference; }
    public boolean isSimulated() { return simulated; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
