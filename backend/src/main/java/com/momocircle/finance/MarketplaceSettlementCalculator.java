package com.momocircle.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The ONE settlement formula for every marketplace domain (retail, food, stock, services). BigDecimal at 2 dp, no floating
 * point. With zero payment fee and zero adjustments it produces exactly the same numbers as the food
 * {@link SettlementCalculator} (asserted in tests), so Food results do not change.
 *
 *   eligible   = max(0, gross - refunded)
 *   commission = eligible x commissionRate
 *   fixedFee   = min(fixedFee, remaining after commission)
 *   paymentFee = min(eligible x rate + fixed, remaining after commission and fixed fee)
 *   net        = max(0, eligible - commission - fixedFee - paymentFee + adjustments)
 */
public final class MarketplaceSettlementCalculator {

    private MarketplaceSettlementCalculator() {}

    public record Rates(BigDecimal commissionRate, BigDecimal fixedFee, BigDecimal paymentFeeRate, BigDecimal paymentFeeFixed) {
        public Rates {
            if (commissionRate == null || commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("commission rate must be between 0 and 1");
            }
            if (paymentFeeRate == null || paymentFeeRate.signum() < 0 || paymentFeeRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("payment fee rate must be between 0 and 1");
            }
            if (fixedFee == null || fixedFee.signum() < 0 || paymentFeeFixed == null || paymentFeeFixed.signum() < 0) {
                throw new IllegalArgumentException("fees must not be negative");
            }
        }

        public static Rates of(BigDecimal commissionRate, BigDecimal fixedFee) {
            return new Rates(commissionRate, fixedFee, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record Result(BigDecimal gross, BigDecimal refunded, BigDecimal eligible, BigDecimal commissionRate,
                         BigDecimal commission, BigDecimal fixedFee, BigDecimal paymentFee, BigDecimal adjustments,
                         BigDecimal net) {}

    public static Result calculate(BigDecimal gross, BigDecimal refunded, BigDecimal adjustments, Rates rates) {
        if (gross == null || gross.signum() < 0) {
            throw new IllegalArgumentException("gross amount must not be negative");
        }
        BigDecimal g = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal r = (refunded == null ? BigDecimal.ZERO : refunded).setScale(2, RoundingMode.HALF_UP);
        BigDecimal adj = (adjustments == null ? BigDecimal.ZERO : adjustments).setScale(2, RoundingMode.HALF_UP);
        BigDecimal eligible = g.subtract(r).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = eligible.multiply(rates.commissionRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterCommission = eligible.subtract(commission).max(BigDecimal.ZERO);
        BigDecimal fixed = rates.fixedFee().min(afterCommission).setScale(2, RoundingMode.HALF_UP);
        BigDecimal wantedPaymentFee = eligible.multiply(rates.paymentFeeRate()).add(rates.paymentFeeFixed())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal paymentFee = wantedPaymentFee.min(afterCommission.subtract(fixed).max(BigDecimal.ZERO))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = eligible.subtract(commission).subtract(fixed).subtract(paymentFee);
        BigDecimal net = base.add(adj).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new Result(g, r, eligible, rates.commissionRate(), commission, fixed, paymentFee, adj, net);
    }
}
