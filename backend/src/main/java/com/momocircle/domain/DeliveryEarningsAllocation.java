package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * PULA Food: how one PULA Ride food-delivery fee was split. Created once per delivery (unique FK),
 * from the order's delivery-fee snapshot. driverShare is what the driver earns; pulaShare is PULA's
 * delivery-fee income. Neither is restaurant food sales.
 */
@Entity
@Table(name = "delivery_earnings_allocations")
public class DeliveryEarningsAllocation extends BaseEntity {

    public static final String POLICY_PULA_RIDE_80_20 = "PULA_RIDE_80_20";

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_request_id", nullable = false, unique = true)
    private DeliveryRequest deliveryRequest;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal deliveryFeeSnapshot;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal driverShare;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pulaShare;

    @Column(nullable = false, length = 32)
    private String splitPolicy = POLICY_PULA_RIDE_80_20;

    public DeliveryRequest getDeliveryRequest() { return deliveryRequest; }
    public void setDeliveryRequest(DeliveryRequest deliveryRequest) { this.deliveryRequest = deliveryRequest; }
    public BigDecimal getDeliveryFeeSnapshot() { return deliveryFeeSnapshot; }
    public void setDeliveryFeeSnapshot(BigDecimal v) { this.deliveryFeeSnapshot = v; }
    public BigDecimal getDriverShare() { return driverShare; }
    public void setDriverShare(BigDecimal v) { this.driverShare = v; }
    public BigDecimal getPulaShare() { return pulaShare; }
    public void setPulaShare(BigDecimal v) { this.pulaShare = v; }
    public String getSplitPolicy() { return splitPolicy; }
    public void setSplitPolicy(String splitPolicy) { this.splitPolicy = splitPolicy; }
}
