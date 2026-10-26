package com.momocircle.domain.ledger;

import com.momocircle.ledger.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent form of {@link Account}. Maps table {@code ledger_accounts} (V40). Identity columns are
 * {@code updatable = false}; {@code balanceMinor} is a projection of the postings changed only by
 * {@code LedgerAccountRepository#addToBalance} inside the posting transaction (never edited independently).
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccountEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 24, nullable = false, updatable = false)
    private Account.Kind kind;

    @Column(name = "owner_user_id", length = 36, updatable = false)
    private String ownerUserId;

    @Column(name = "owner_business_id", length = 36, updatable = false)
    private String ownerBusinessId;

    @Column(name = "system_key", length = 64, updatable = false)
    private String systemKey;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccountEntity() {
        // JPA
    }

    public static LedgerAccountEntity wallet(String id, String ownerUserId, String currency, Instant now) {
        LedgerAccountEntity e = new LedgerAccountEntity();
        e.id = id;
        e.kind = Account.Kind.USER_WALLET;
        e.ownerUserId = ownerUserId;
        e.currency = currency;
        e.allowNegative = false;
        e.balanceMinor = 0L;
        e.createdAt = now;
        return e;
    }

    public static LedgerAccountEntity businessWallet(String id, String ownerBusinessId, String currency, Instant now) {
        LedgerAccountEntity e = new LedgerAccountEntity();
        e.id = id;
        e.kind = Account.Kind.BUSINESS_WALLET;
        e.ownerBusinessId = ownerBusinessId;
        e.currency = currency;
        e.allowNegative = false;
        e.balanceMinor = 0L;
        e.createdAt = now;
        return e;
    }

    public static LedgerAccountEntity system(String id, Account.Kind kind, String currency, Instant now) {
        if (kind == Account.Kind.USER_WALLET || kind == Account.Kind.BUSINESS_WALLET) {
            throw new IllegalArgumentException("Not a system account kind");
        }
        LedgerAccountEntity e = new LedgerAccountEntity();
        e.id = id;
        e.kind = kind;
        e.systemKey = kind.name() + ":" + currency;
        e.currency = currency;
        e.allowNegative = true;
        e.balanceMinor = 0L;
        e.createdAt = now;
        return e;
    }

    public Account toDomain() {
        return new Account(id, kind, ownerUserId, ownerBusinessId, currency, allowNegative);
    }

    public String getId() { return id; }
    public Account.Kind getKind() { return kind; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getOwnerBusinessId() { return ownerBusinessId; }
    public String getCurrency() { return currency; }
    public boolean isAllowNegative() { return allowNegative; }
    public long getBalanceMinor() { return balanceMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
