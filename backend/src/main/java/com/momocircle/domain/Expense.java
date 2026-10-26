package com.momocircle.domain;

import com.momocircle.domain.enums.ExpenseStatus;
import com.momocircle.domain.enums.SplitType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A shared cost fronted by one member ({@code paidBy}) and split among participants as {@link ExpenseShare}s.
 *
 * <p>{@link #getAmount()} is the RECORDED cost. It is not money held or moved by PULA: simulated funds move only when a
 * participant confirms a share and the shared ledger commits a transfer (see {@link ExpenseShare#getLedgerTransactionId()}).
 */
@Entity
@Table(name = "expenses")
public class Expense extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paid_by_id", nullable = false)
    private User paidBy;

    /** Who recorded the expense (may differ from the payer only when a circle admin records it on someone's behalf). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    /** The expense title (kept under its original column name). */
    @Column(nullable = false)
    private String description;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SplitType splitType = SplitType.EQUAL;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExpenseStatus status = ExpenseStatus.ACTIVE;

    /** Incremented by every accepted edit; the audit history records which revision each event belongs to. */
    @Column(nullable = false)
    private int revision = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "client_request_key", length = 100, updatable = false)
    private String clientRequestKey;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt asc")
    private List<ExpenseShare> shares = new ArrayList<>();

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position asc")
    private List<ExpenseItem> items = new ArrayList<>();

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }

    public User getPaidBy() { return paidBy; }
    public void setPaidBy(User paidBy) { this.paidBy = paidBy; }

    public User getCreatedBy() { return createdBy == null ? paidBy : createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public SplitType getSplitType() { return splitType; }
    public void setSplitType(SplitType splitType) { this.splitType = splitType; }

    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public ExpenseStatus getStatus() { return status; }
    public void setStatus(ExpenseStatus status) { this.status = status; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public long getLockVersion() { return lockVersion; }

    public String getClientRequestKey() { return clientRequestKey; }
    public void setClientRequestKey(String clientRequestKey) { this.clientRequestKey = clientRequestKey; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public User getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(User cancelledBy) { this.cancelledBy = cancelledBy; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public List<ExpenseShare> getShares() { return shares; }
    public void setShares(List<ExpenseShare> shares) { this.shares = shares; }

    public List<ExpenseItem> getItems() { return items; }
    public void setItems(List<ExpenseItem> items) { this.items = items; }

    public boolean isCancelled() { return status == ExpenseStatus.CANCELLED; }
}
