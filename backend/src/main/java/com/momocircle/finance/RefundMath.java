package com.momocircle.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Refund amount rules, kept pure so they can be tested exhaustively. */
public final class RefundMath {

    private RefundMath() {}

    /**
     * Validates 0 < amount <= originalPayment and that cumulative refunds (excluding failed ones) never
     * exceed the original payment. Returns the amount normalised to 2 dp.
     */
    public static BigDecimal validate(BigDecimal amount, BigDecimal originalPayment, BigDecimal alreadyRefundedOrInFlight) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (originalPayment == null || originalPayment.signum() <= 0) {
            throw new IllegalArgumentException("There is no payment to refund");
        }
        BigDecimal normalised = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalised.compareTo(originalPayment) > 0) {
            throw new IllegalArgumentException("Refund amount cannot exceed the original payment");
        }
        BigDecimal already = alreadyRefundedOrInFlight == null ? BigDecimal.ZERO : alreadyRefundedOrInFlight;
        if (already.add(normalised).compareTo(originalPayment) > 0) {
            throw new IllegalArgumentException("Total refunds cannot exceed the original payment");
        }
        return normalised;
    }

    public static BigDecimal remaining(BigDecimal originalPayment, BigDecimal alreadyRefundedOrInFlight) {
        BigDecimal already = alreadyRefundedOrInFlight == null ? BigDecimal.ZERO : alreadyRefundedOrInFlight;
        return originalPayment.subtract(already).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Provider minor units (cents) for a 2 dp amount. */
    public static long toMinor(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
