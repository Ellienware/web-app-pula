package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * An immutable record of a sensitive action — admin operations, payment state
 * changes, ownership changes, AI-triggered financial actions. Never stores
 * secrets; `details` is a short human-readable summary, not a payload dump.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    /** Null for system-initiated events (e.g. a webhook-driven state change) rather than a specific user action. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(nullable = false)
    private String action;

    private String targetType;
    private String targetId;

    @Column(length = 1000)
    private String details;

    public User getActor() { return actor; }
    public void setActor(User actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
