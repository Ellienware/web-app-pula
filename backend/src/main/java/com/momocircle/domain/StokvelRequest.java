package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "stokvel_requests")
public class StokvelRequest extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "goal_id")
    private CircleGoal goal;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private StokvelRequestType requestType;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "requester_id", nullable = false)
    private User requester;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "buyer_id")
    private User buyer;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, length = 500)
    private String purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private StokvelRequestStatus status = StokvelRequestStatus.PENDING;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ApprovalRuleType approvalRule;
    @Column(nullable = false)
    private int requiredApprovals;
    @Column(nullable = false)
    private int eligibleVoters;
    @Column(length = 100)
    private String idempotencyKey;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant decidedAt;
    private Instant executedAt;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private RelatedOrderKind relatedOrderKind;
    @Column(length = 36)
    private String relatedOrderId;
    @Column(length = 200)
    private String externalReference;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public CircleGoal getGoal() { return goal; }
    public void setGoal(CircleGoal goal) { this.goal = goal; }
    public StokvelRequestType getRequestType() { return requestType; }
    public void setRequestType(StokvelRequestType requestType) { this.requestType = requestType; }
    public User getRequester() { return requester; }
    public void setRequester(User requester) { this.requester = requester; }
    public User getBuyer() { return buyer; }
    public void setBuyer(User buyer) { this.buyer = buyer; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public StokvelRequestStatus getStatus() { return status; }
    public void setStatus(StokvelRequestStatus status) { this.status = status; }
    public ApprovalRuleType getApprovalRule() { return approvalRule; }
    public void setApprovalRule(ApprovalRuleType approvalRule) { this.approvalRule = approvalRule; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }
    public int getEligibleVoters() { return eligibleVoters; }
    public void setEligibleVoters(int eligibleVoters) { this.eligibleVoters = eligibleVoters; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
    public RelatedOrderKind getRelatedOrderKind() { return relatedOrderKind; }
    public void setRelatedOrderKind(RelatedOrderKind relatedOrderKind) { this.relatedOrderKind = relatedOrderKind; }
    public String getRelatedOrderId() { return relatedOrderId; }
    public void setRelatedOrderId(String relatedOrderId) { this.relatedOrderId = relatedOrderId; }
    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
}
