package com.momocircle.domain;

import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.domain.enums.SettlementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What PULA owes a restaurant for one food order, and the record of paying it. Deliberately separate
 * from {@link DriverPayout}: restaurant revenue and driver earnings are different financial concepts.
 * One row per order (unique), so an order can never be settled twice. The payout provider is reused
 * through the shared PayoutOrchestrator under the RESTAURANT_SETTLEMENT purpose.
 *
 * Every amount is computed by the backend; no client supplies any of them.
 */
@Entity
@Table(name = "restaurant_settlements")
public class RestaurantSettlement extends BaseEntity {

    /** Set for retail and food settlements (one per order); null for stock orders and service bookings. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", unique = true)
    private Order order;

    /** What was sold. With {@link #sourceId} this is the unique, deterministic identity of the settlement. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 24)
    private com.momocircle.domain.enums.SettlementSourceType sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER;

    @Column(name = "source_id", length = 36)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "seller_kind", nullable = false, length = 24)
    private com.momocircle.domain.enums.SellerKind sellerKind = com.momocircle.domain.enums.SellerKind.RESTAURANT;

    /** Gross sales amount of the source (for food this equals foodSubtotal). Delivery fees are never included. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "payment_fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paymentFeeAmount = BigDecimal.ZERO;

    /** Sum of this settlement's append-only adjustments (signed). */
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustmentAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SettlementType type = SettlementType.SALE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SettlementStatus status = SettlementStatus.NOT_ELIGIBLE;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Food revenue of the order (items + extras); the delivery fee is never part of it. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal foodSubtotal = BigDecimal.ZERO;

    /** Informational: the customer's delivery fee, which belongs to the driver/PULA split, not the restaurant. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal deliveryAllocation = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal eligibleRevenue = BigDecimal.ZERO;

    /** Rate snapshot taken at calculation time so later config changes never rewrite history. */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal otherFees = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** MERCHANT_PROTECTION only: the amount PULA absorbs (equal to the compensation paid out). */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pulaFulfilmentLoss = BigDecimal.ZERO;

    /** Why a merchant-protection / cancelled settlement exists. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CancellationCause cause;

    @Column(length = 40)
    private String payoutProvider;

    @Column(length = 200)
    private String payoutReference;

    @Column(nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    @Column(nullable = false)
    private int payoutAttempts = 0;

    @Column(length = 500)
    private String failureReason;

    @Column(length = 500)
    private String holdReason;

    private Instant eligibleAt;
    private Instant paidAt;

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public SettlementType getType() { return type; }
    public void setType(SettlementType type) { this.type = type; }
    public SettlementStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getFoodSubtotal() { return foodSubtotal; }
    public void setFoodSubtotal(BigDecimal v) { this.foodSubtotal = v; }
    public BigDecimal getDeliveryAllocation() { return deliveryAllocation; }
    public void setDeliveryAllocation(BigDecimal v) { this.deliveryAllocation = v; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal v) { this.refundAmount = v; }
    public BigDecimal getEligibleRevenue() { return eligibleRevenue; }
    public void setEligibleRevenue(BigDecimal v) { this.eligibleRevenue = v; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal v) { this.commissionRate = v; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal v) { this.commissionAmount = v; }
    public BigDecimal getOtherFees() { return otherFees; }
    public void setOtherFees(BigDecimal v) { this.otherFees = v; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal v) { this.netAmount = v; }
    public BigDecimal getPulaFulfilmentLoss() { return pulaFulfilmentLoss; }
    public void setPulaFulfilmentLoss(BigDecimal v) { this.pulaFulfilmentLoss = v; }
    public CancellationCause getCause() { return cause; }
    public void setCause(CancellationCause cause) { this.cause = cause; }
    public String getPayoutProvider() { return payoutProvider; }
    public void setPayoutProvider(String payoutProvider) { this.payoutProvider = payoutProvider; }
    public String getPayoutReference() { return payoutReference; }
    public void setPayoutReference(String payoutReference) { this.payoutReference = payoutReference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getPayoutAttempts() { return payoutAttempts; }
    public void setPayoutAttempts(int payoutAttempts) { this.payoutAttempts = payoutAttempts; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String r) { this.failureReason = r == null || r.length() <= 500 ? r : r.substring(0, 500); }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
    public Instant getEligibleAt() { return eligibleAt; }
    public void setEligibleAt(Instant eligibleAt) { this.eligibleAt = eligibleAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    /** Legal transitions only. PAID can only be reached through the provider-confirmed path. */
    public void transitionTo(SettlementStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal settlement status transition: " + status + " -> " + next + " (settlement " + getId() + ")");
        }
        this.status = next;
    }

    public com.momocircle.domain.enums.SettlementSourceType getSourceType() { return sourceType; }
    public void setSourceType(com.momocircle.domain.enums.SettlementSourceType v) { this.sourceType = v; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { this.sourceId = v; }
    public com.momocircle.domain.enums.SellerKind getSellerKind() { return sellerKind; }
    public void setSellerKind(com.momocircle.domain.enums.SellerKind v) { this.sellerKind = v; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal v) { this.grossAmount = v; }
    public BigDecimal getPaymentFeeAmount() { return paymentFeeAmount; }
    public void setPaymentFeeAmount(BigDecimal v) { this.paymentFeeAmount = v; }
    public BigDecimal getAdjustmentAmount() { return adjustmentAmount; }
    public void setAdjustmentAmount(BigDecimal v) { this.adjustmentAmount = v; }
}
