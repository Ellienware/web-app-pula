package com.momocircle.settlement;

import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Per-domain commission, fee and dispute-window configuration for retail, stock and services (Food keeps its own
 * app.food.settlement.*). EVERY value is a placeholder 0 until PULA decides the commercial terms; nothing is hard-coded.
 * Bound from app.marketplace.settlement.{retail|stock|service}.*.
 */
@Component
@ConfigurationProperties(prefix = "app.marketplace.settlement")
public class MarketplaceSettlementProperties {

    public static class Domain {
        private BigDecimal commissionRate = BigDecimal.ZERO;
        private BigDecimal fixedFee = BigDecimal.ZERO;
        private BigDecimal paymentFeeRate = BigDecimal.ZERO;
        private BigDecimal paymentFeeFixed = BigDecimal.ZERO;
        private int disputeWindowMinutes = 0;

        public BigDecimal getCommissionRate() { return commissionRate; }
        public void setCommissionRate(BigDecimal v) { this.commissionRate = v; }
        public BigDecimal getFixedFee() { return fixedFee; }
        public void setFixedFee(BigDecimal v) { this.fixedFee = v; }
        public BigDecimal getPaymentFeeRate() { return paymentFeeRate; }
        public void setPaymentFeeRate(BigDecimal v) { this.paymentFeeRate = v; }
        public BigDecimal getPaymentFeeFixed() { return paymentFeeFixed; }
        public void setPaymentFeeFixed(BigDecimal v) { this.paymentFeeFixed = v; }
        public int getDisputeWindowMinutes() { return disputeWindowMinutes; }
        public void setDisputeWindowMinutes(int v) { this.disputeWindowMinutes = Math.max(0, v); }

        public Rates rates() {
            return new Rates(commissionRate, fixedFee, paymentFeeRate, paymentFeeFixed);
        }
    }

    private Domain retail = new Domain();
    private Domain stock = new Domain();
    private Domain service = new Domain();
    /** Max attempts for a failed payout before it needs a human (shared by every source). */
    private int maxPayoutAttempts = 5;
    /** A settlement stuck in PROCESSING longer than this is re-driven (the provider call is idempotent). */
    private int stuckProcessingMinutes = 15;

    public Domain getRetail() { return retail; }
    public void setRetail(Domain v) { this.retail = v; }
    public Domain getStock() { return stock; }
    public void setStock(Domain v) { this.stock = v; }
    public Domain getService() { return service; }
    public void setService(Domain v) { this.service = v; }
    public int getMaxPayoutAttempts() { return maxPayoutAttempts; }
    public void setMaxPayoutAttempts(int v) { this.maxPayoutAttempts = Math.max(1, v); }
    public int getStuckProcessingMinutes() { return stuckProcessingMinutes; }
    public void setStuckProcessingMinutes(int v) { this.stuckProcessingMinutes = Math.max(1, v); }
}
