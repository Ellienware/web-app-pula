package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One immutable entry in an expense's audit history. Rows are only ever inserted. {@code ledgerReference} is the public
 * reference of the shared-ledger transaction when the event is a simulated money movement.
 */
@Entity
@Table(name = "expense_events")
public class ExpenseEvent extends BaseEntity {

    @Column(name = "expense_id", nullable = false, length = 36, updatable = false)
    private String expenseId;

    @Column(name = "share_id", length = 36, updatable = false)
    private String shareId;

    @Column(name = "actor_user_id", length = 36, updatable = false)
    private String actorUserId;

    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private int revision;

    @Column(length = 1000, updatable = false)
    private String details;

    @Column(name = "ledger_reference", length = 40, updatable = false)
    private String ledgerReference;

    public static ExpenseEvent of(String expenseId, String shareId, String actorUserId, String eventType, int revision,
                                  String details, String ledgerReference) {
        ExpenseEvent e = new ExpenseEvent();
        e.expenseId = expenseId;
        e.shareId = shareId;
        e.actorUserId = actorUserId;
        e.eventType = eventType;
        e.revision = revision;
        e.details = details == null ? null : (details.length() > 1000 ? details.substring(0, 1000) : details);
        e.ledgerReference = ledgerReference;
        return e;
    }

    public String getExpenseId() { return expenseId; }
    public String getShareId() { return shareId; }
    public String getActorUserId() { return actorUserId; }
    public String getEventType() { return eventType; }
    public int getRevision() { return revision; }
    public String getDetails() { return details; }
    public String getLedgerReference() { return ledgerReference; }
}
