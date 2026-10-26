package com.momocircle.domain;

import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One participant's portion of an {@link Expense}. It doubles as that participant's split-bill payment request: it has an
 * amount, a requester (the expense payer), a consent status, and, once settled through the wallet, the id of the shared-ledger
 * transaction that moved the simulated funds. Status, ledger link and claim columns are changed ONLY by guarded UPDATEs in
 * {@code ExpenseShareRepository}, never by overwriting a loaded entity, so concurrent attempts cannot both win.
 */
@Entity
@Table(name = "expense_shares")
public class ExpenseShare extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debtor_id", nullable = false)
    private User debtor;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** True exactly when {@link #getStatus()} is PAID (kept for the original column's consumers). */
    @Column(nullable = false)
    private boolean settled = false;

    @Column
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareStatus status = ShareStatus.PENDING;

    @Column(name = "percentage_bp")
    private Integer percentageBp;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_method", length = 16)
    private SettlementMethod settlementMethod;

    @Column(name = "ledger_transaction_id", length = 36)
    private String ledgerTransactionId;

    @Column(name = "refund_transaction_id", length = 36)
    private String refundTransactionId;

    @Column(name = "claim_user_id", length = 36)
    private String claimUserId;

    /** Incremented after a refund/reversal so a later payment of the same share uses a fresh ledger idempotency key. */
    @Column(name = "settle_round", nullable = false)
    private int settleRound = 0;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "response_note", length = 300)
    private String responseNote;

    @Column(name = "settled_by_id", length = 36)
    private String settledById;

    public Expense getExpense() { return expense; }
    public void setExpense(Expense expense) { this.expense = expense; }

    public User getDebtor() { return debtor; }
    public void setDebtor(User debtor) { this.debtor = debtor; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public boolean isSettled() { return settled; }
    public void setSettled(boolean settled) { this.settled = settled; }

    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    public ShareStatus getStatus() { return status; }
    public void setStatus(ShareStatus status) { this.status = status; }

    public Integer getPercentageBp() { return percentageBp; }
    public void setPercentageBp(Integer percentageBp) { this.percentageBp = percentageBp; }

    public SettlementMethod getSettlementMethod() { return settlementMethod; }
    public void setSettlementMethod(SettlementMethod settlementMethod) { this.settlementMethod = settlementMethod; }

    public String getLedgerTransactionId() { return ledgerTransactionId; }
    public void setLedgerTransactionId(String ledgerTransactionId) { this.ledgerTransactionId = ledgerTransactionId; }

    public String getRefundTransactionId() { return refundTransactionId; }
    public void setRefundTransactionId(String refundTransactionId) { this.refundTransactionId = refundTransactionId; }

    public String getClaimUserId() { return claimUserId; }
    public void setClaimUserId(String claimUserId) { this.claimUserId = claimUserId; }

    public int getSettleRound() { return settleRound; }
    public void setSettleRound(int settleRound) { this.settleRound = settleRound; }

    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }

    public String getResponseNote() { return responseNote; }
    public void setResponseNote(String responseNote) { this.responseNote = responseNote; }

    public String getSettledById() { return settledById; }
    public void setSettledById(String settledById) { this.settledById = settledById; }
}
