package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Append-only audit trail of material purchasing decisions. Rows are never updated or deleted. */
@Entity
@Table(name = "bulk_group_events")
public class BulkGroupEvent extends BaseEntity {

    @Column(name = "group_id", nullable = false, length = 36, updatable = false)
    private String groupId;
    /** The business this event is about; null for group-level events visible to every party with access. */
    @Column(name = "business_id", length = 36, updatable = false)
    private String businessId;
    @Column(name = "actor_user_id", length = 36, updatable = false)
    private String actorUserId;
    @Column(nullable = false, length = 40, updatable = false)
    private String type;
    @Column(length = 500, updatable = false)
    private String message;
    @Column(updatable = false)
    private Integer packs;
    @Column(precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { this.groupId = v; }
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String v) { this.businessId = v; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String v) { this.actorUserId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public Integer getPacks() { return packs; }
    public void setPacks(Integer v) { this.packs = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
}
