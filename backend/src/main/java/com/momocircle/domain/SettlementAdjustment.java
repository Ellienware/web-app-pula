package com.momocircle.domain;

import com.momocircle.domain.enums.AdjustmentKind;
import jakarta.persistence.*;

import java.math.BigDecimal;

/** An immutable, signed correction to a settlement that has not been paid out. Never edited, never deleted. */
@Entity
@Table(name = "settlement_adjustments")
public class SettlementAdjustment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false, updatable = false)
    private RestaurantSettlement settlement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AdjustmentKind kind;

    /** Signed: positive increases what the seller is paid, negative reduces it. */
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 300, updatable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    private User createdBy;

    @Column(nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    public RestaurantSettlement getSettlement() { return settlement; }
    public void setSettlement(RestaurantSettlement settlement) { this.settlement = settlement; }
    public AdjustmentKind getKind() { return kind; }
    public void setKind(AdjustmentKind kind) { this.kind = kind; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
