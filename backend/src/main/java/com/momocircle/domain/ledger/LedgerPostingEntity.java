package com.momocircle.domain.ledger;

import com.momocircle.ledger.Posting;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One immutable ledger line. Maps table {@code ledger_postings} (V40). {@link Immutable} stops Hibernate from ever
 * issuing an UPDATE; the repository exposes no delete. Database-level immutability (UPDATE/DELETE/TRUNCATE rejection) is
 * provided by the PostgreSQL guards migration, which is opt-in; see docs/PHASE_9A_REPORT.md.
 */
@Entity
@Immutable
@Table(name = "ledger_postings")
public class LedgerPostingEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "transaction_id", length = 36, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "account_id", length = 36, nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 6, nullable = false, updatable = false)
    private Posting.Direction direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerPostingEntity() {
        // JPA
    }

    public static LedgerPostingEntity of(String id, String transactionId, int lineNo, Posting p, Instant createdAt) {
        LedgerPostingEntity e = new LedgerPostingEntity();
        e.id = id;
        e.transactionId = transactionId;
        e.lineNo = lineNo;
        e.accountId = p.accountId();
        e.direction = p.direction();
        e.amountMinor = p.minor();
        e.currency = p.currency();
        e.createdAt = createdAt;
        return e;
    }

    public Posting toDomain() {
        return new Posting(accountId, direction, amountMinor, currency);
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public int getLineNo() { return lineNo; }
    public String getAccountId() { return accountId; }
    public Posting.Direction getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
