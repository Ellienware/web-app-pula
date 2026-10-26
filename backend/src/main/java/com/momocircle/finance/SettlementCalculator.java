package com.momocircle.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Backend-authoritative restaurant settlement arithmetic. Pure and deterministic; clients never supply
 * any input. Delivery fees are never restaurant revenue and are not part of the calculation.
 *
 *   eligibleRevenue = max(0, foodSubtotal - refundedAmount)
 *   commission      = eligibleRevenue x commissionRate        (HALF_UP, 2 dp)
 *   net             = max(0, eligibleRevenue - commission - otherFees)
 *
 * Refunds reduce food revenue first and conservatively (the restaurant is not paid for money the
 * customer got back). A refund of the delivery fee alone would also reduce it; this is documented.
 */
public final class SettlementCalculator {

    private SettlementCalculator() {}

    public record Rates(BigDecimal commissionRate, BigDecimal fixedFee) {
        public Rates {
            if (commissionRate == null || commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("commission rate must be between 0 and 1");
            }
            if (fixedFee == null || fixedFee.signum() < 0) {
                throw new IllegalArgumentException("fixed fee must not be negative");
            }
        }
    }

    public record Result(BigDecimal foodSubtotal, BigDecimal refundedAmount, BigDecimal eligibleRevenue,
                         BigDecimal commissionRate, BigDecimal commission, BigDecimal otherFees, BigDecimal net) {}

    public static Result calculate(BigDecimal foodSubtotal, BigDecimal refundedAmount, Rates rates) {
        if (foodSubtotal == null || foodSubtotal.signum() < 0) {
            throw new IllegalArgumentException("food subtotal must not be negative");
        }
        BigDecimal refunded = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
        BigDecimal subtotal = foodSubtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal eligible = subtotal.subtract(refunded).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = eligible.multiply(rates.commissionRate()).setScale(2, RoundingMode.HALF_UP);
        // Fees can never exceed what the restaurant earned: the net is floored at zero, never negative.
        BigDecimal otherFees = rates.fixedFee().min(eligible.subtract(commission).max(BigDecimal.ZERO))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = eligible.subtract(commission).subtract(otherFees).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new Result(subtotal, refunded.setScale(2, RoundingMode.HALF_UP), eligible,
                rates.commissionRate(), commission, otherFees, net);
    }
}
