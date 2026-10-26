package com.momocircle.domain;

import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.momoride.Driver;
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

/** PULA -> driver earnings payout for one completed delivery. One per delivery (unique). */
@Entity
@Table(name = "driver_payouts")
public class DriverPayout extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_request_id", nullable = false, unique = true)
    private DeliveryRequest deliveryRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PayoutStatus status = PayoutStatus.PENDING;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(length = 200)
    private String providerReference;

    @Column(nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private int attempts = 1;

    public void transitionTo(PayoutStatus next) {
        if (next == status) return;
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal payout transition " + status + " -> " + next);
        }
        this.status = next;
    }

    public DeliveryRequest getDeliveryRequest() { return deliveryRequest; }
    public void setDeliveryRequest(DeliveryRequest deliveryRequest) { this.deliveryRequest = deliveryRequest; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PayoutStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
