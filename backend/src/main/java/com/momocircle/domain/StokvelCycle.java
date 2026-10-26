package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "stokvel_cycles")
public class StokvelCycle extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @Column(nullable = false)
    private int cycleNumber;
    @Column(nullable = false)
    private int payoutPosition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "beneficiary_id", nullable = false)
    private User beneficiary;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recorded_by_id", nullable = false)
    private User recordedBy;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedAmount;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal collectedAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private StokvelCycleStatus status = StokvelCycleStatus.PAID_OUT;
    @Column(length = 100)
    private String reference;
    @Column(nullable = false)
    private Instant paidAt;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public int getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(int cycleNumber) { this.cycleNumber = cycleNumber; }
    public int getPayoutPosition() { return payoutPosition; }
    public void setPayoutPosition(int payoutPosition) { this.payoutPosition = payoutPosition; }
    public User getBeneficiary() { return beneficiary; }
    public void setBeneficiary(User beneficiary) { this.beneficiary = beneficiary; }
    public User getRecordedBy() { return recordedBy; }
    public void setRecordedBy(User recordedBy) { this.recordedBy = recordedBy; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }
    public BigDecimal getCollectedAmount() { return collectedAmount; }
    public void setCollectedAmount(BigDecimal collectedAmount) { this.collectedAmount = collectedAmount; }
    public StokvelCycleStatus getStatus() { return status; }
    public void setStatus(StokvelCycleStatus status) { this.status = status; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
