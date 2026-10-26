package com.momocircle.domain;

import com.momocircle.domain.enums.*;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ties a savings goal to a marketplace purchase. It is a TRACKING record: PULA does not hold the savings, and the
 * actual order/payment are created by the existing checkout. {@code requestedAmount} is immutable.
 */
@Entity
@Table(name = "save_to_buy_requests")
public class SaveToBuyRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "goal_id", nullable = false)
    private CircleGoal goal;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private SaveToBuyScope scope;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private SaveToBuyVertical vertical;
    @Column(nullable = false, length = 500)
    private String purpose;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal requestedAmount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal goalTargetSnapshot;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "named_buyer_id", nullable = false)
    private User namedBuyer;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private SaveToBuyStatus status = SaveToBuyStatus.DRAFT;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "stokvel_request_id", unique = true)
    private StokvelRequest stokvelRequest;
    @Column(length = 100)
    private String idempotencyKey;
    private Instant expiresAt;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private RelatedOrderKind orderKind;
    @Column(length = 36)
    private String orderId;
    @Column(length = 200)
    private String checkoutKey;
    @Column(precision = 19, scale = 2)
    private BigDecimal purchasedAmount;
    private Instant purchasedAt;
    @Column(length = 200)
    private String completionReference;

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public CircleGoal getGoal() { return goal; }
    public void setGoal(CircleGoal goal) { this.goal = goal; }
    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public SaveToBuyScope getScope() { return scope; }
    public void setScope(SaveToBuyScope scope) { this.scope = scope; }
    public SaveToBuyVertical getVertical() { return vertical; }
    public void setVertical(SaveToBuyVertical vertical) { this.vertical = vertical; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getGoalTargetSnapshot() { return goalTargetSnapshot; }
    public void setGoalTargetSnapshot(BigDecimal goalTargetSnapshot) { this.goalTargetSnapshot = goalTargetSnapshot; }
    public User getNamedBuyer() { return namedBuyer; }
    public void setNamedBuyer(User namedBuyer) { this.namedBuyer = namedBuyer; }
    public SaveToBuyStatus getStatus() { return status; }
    public void setStatus(SaveToBuyStatus status) { this.status = status; }
    public StokvelRequest getStokvelRequest() { return stokvelRequest; }
    public void setStokvelRequest(StokvelRequest stokvelRequest) { this.stokvelRequest = stokvelRequest; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public RelatedOrderKind getOrderKind() { return orderKind; }
    public void setOrderKind(RelatedOrderKind orderKind) { this.orderKind = orderKind; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getCheckoutKey() { return checkoutKey; }
    public void setCheckoutKey(String checkoutKey) { this.checkoutKey = checkoutKey; }
    public BigDecimal getPurchasedAmount() { return purchasedAmount; }
    public void setPurchasedAmount(BigDecimal purchasedAmount) { this.purchasedAmount = purchasedAmount; }
    public Instant getPurchasedAt() { return purchasedAt; }
    public void setPurchasedAt(Instant purchasedAt) { this.purchasedAt = purchasedAt; }
    public String getCompletionReference() { return completionReference; }
    public void setCompletionReference(String completionReference) { this.completionReference = completionReference; }
}
