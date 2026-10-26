package com.momocircle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * PULA Food restaurant settlement and merchant-protection policy. EVERY value is a placeholder to be
 * confirmed by PULA before launch; none is hardcoded in service code. Bound from app.food.settlement.*.
 */
@Component
@ConfigurationProperties(prefix = "app.food.settlement")
public class SettlementProperties {

    /** Share of eligible food revenue PULA keeps (0.00 - 1.00). Placeholder 0 until the commercial rate is decided. */
    private BigDecimal commissionRate = BigDecimal.ZERO;
    /** Flat per-order fee deducted from the restaurant (never more than the earned amount). */
    private BigDecimal fixedFee = BigDecimal.ZERO;
    /** Minutes between order COMPLETED and the settlement becoming payable: PULA's dispute window. 0 = payable at once. */
    private int delayMinutes = 0;
    /** Commission rate applied to merchant-protection compensation ("minus any configured commission/adjustment"). */
    private BigDecimal compensationCommissionRate = BigDecimal.ZERO;
    /** Compensate prepared food when PULA found no driver (PULA_DELIVERY_FAILURE). */
    private boolean compensatePulaDeliveryFailure = true;
    /** Compensate prepared food when the customer cancelled after preparation. Off by default. */
    private boolean compensateCustomerCancelledAfterPrep = false;
    /** Compensate prepared food when PULA support cancelled the order. Off by default; admins decide case by case. */
    private boolean compensateAdminCancelledAfterPrep = false;
    /** A payout attempt stuck in PROCESSING longer than this is re-driven (the provider call is idempotent). */
    private int stuckProcessingMinutes = 10;
    /** After this many failed payout attempts a settlement stays FAILED for an admin to look at. */
    private int maxPayoutAttempts = 5;

    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal v) { this.commissionRate = v; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal v) { this.fixedFee = v; }
    public int getDelayMinutes() { return delayMinutes; }
    public void setDelayMinutes(int v) { this.delayMinutes = v; }
    public BigDecimal getCompensationCommissionRate() { return compensationCommissionRate; }
    public void setCompensationCommissionRate(BigDecimal v) { this.compensationCommissionRate = v; }
    public boolean isCompensatePulaDeliveryFailure() { return compensatePulaDeliveryFailure; }
    public void setCompensatePulaDeliveryFailure(boolean v) { this.compensatePulaDeliveryFailure = v; }
    public boolean isCompensateCustomerCancelledAfterPrep() { return compensateCustomerCancelledAfterPrep; }
    public void setCompensateCustomerCancelledAfterPrep(boolean v) { this.compensateCustomerCancelledAfterPrep = v; }
    public boolean isCompensateAdminCancelledAfterPrep() { return compensateAdminCancelledAfterPrep; }
    public void setCompensateAdminCancelledAfterPrep(boolean v) { this.compensateAdminCancelledAfterPrep = v; }
    public int getStuckProcessingMinutes() { return stuckProcessingMinutes; }
    public void setStuckProcessingMinutes(int v) { this.stuckProcessingMinutes = v; }
    public int getMaxPayoutAttempts() { return maxPayoutAttempts; }
    public void setMaxPayoutAttempts(int v) { this.maxPayoutAttempts = v; }
}
