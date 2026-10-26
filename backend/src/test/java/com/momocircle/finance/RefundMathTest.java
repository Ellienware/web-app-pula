package com.momocircle.finance;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RefundMathTest {
    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void fullRefundOfTheWholePaymentIsValid() {
        assertEquals(d("120.00"), RefundMath.validate(d("120"), d("120.00"), BigDecimal.ZERO));
    }

    @Test
    void partialRefundIsValid() {
        assertEquals(d("20.00"), RefundMath.validate(d("20"), d("120.00"), BigDecimal.ZERO));
    }

    @Test
    void zeroNegativeAndMissingAmountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(d("0"), d("120"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(d("-5"), d("120"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(null, d("120"), BigDecimal.ZERO));
    }

    @Test
    void amountAboveThePaymentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(d("120.01"), d("120.00"), BigDecimal.ZERO));
    }

    @Test
    void cumulativeRefundsCanNeverExceedThePayment() {
        assertEquals(d("50.00"), RefundMath.validate(d("50"), d("120"), d("70"))); // 70 + 50 = 120: exactly the payment
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(d("50.01"), d("120"), d("70")));
        assertThrows(IllegalArgumentException.class, () -> RefundMath.validate(d("1"), d("120"), d("120")));
    }

    @Test
    void remainingIsPaymentMinusInFlightAndNeverNegative() {
        assertEquals(d("50.00"), RefundMath.remaining(d("120"), d("70")));
        assertEquals(d("0.00"), RefundMath.remaining(d("120"), d("130")));
    }

    @Test
    void minorUnitsAreExactForTwoDecimalAmounts() {
        assertEquals(12000L, RefundMath.toMinor(d("120.00")));
        assertEquals(8500L, RefundMath.toMinor(d("85")));
        assertEquals(1L, RefundMath.toMinor(d("0.01")));
        assertEquals(8500L, RefundMath.toMinor(d("85.00")));
    }
}
