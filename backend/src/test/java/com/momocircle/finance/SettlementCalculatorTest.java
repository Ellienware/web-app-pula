package com.momocircle.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.finance.SettlementCalculator.Rates;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }
    private static final Rates NO_FEES = new Rates(d("0"), d("0"));

    @Test
    void deliveryFeeIsNeverRestaurantRevenue() {
        // Customer paid R120 = R100 food + R20 delivery. Only the R100 food is an input: the delivery fee cannot leak in.
        var r = SettlementCalculator.calculate(d("100.00"), null, NO_FEES);
        assertEquals(d("100.00"), r.eligibleRevenue());
        assertEquals(d("100.00"), r.net());
    }

    @Test
    void commissionIsConfigurableAndRoundedHalfUp() {
        var r = SettlementCalculator.calculate(d("100.00"), null, new Rates(d("0.125"), d("0")));
        assertEquals(d("12.50"), r.commission());
        assertEquals(d("87.50"), r.net());
        var odd = SettlementCalculator.calculate(d("33.33"), null, new Rates(d("0.10"), d("0")));
        assertEquals(d("3.33"), odd.commission()); // 3.333 -> 3.33
        assertEquals(d("30.00"), odd.net());
    }

    @Test
    void differentRatesGiveDifferentResultsSoNothingIsHardcoded() {
        assertEquals(d("100.00"), SettlementCalculator.calculate(d("100"), null, new Rates(d("0"), d("0"))).net());
        assertEquals(d("85.00"), SettlementCalculator.calculate(d("100"), null, new Rates(d("0.15"), d("0"))).net());
        assertEquals(d("70.00"), SettlementCalculator.calculate(d("100"), null, new Rates(d("0.30"), d("0"))).net());
    }

    @Test
    void fixedFeeIsDeductedButNeverExceedsWhatWasEarned() {
        assertEquals(d("97.00"), SettlementCalculator.calculate(d("100"), null, new Rates(d("0"), d("3"))).net());
        var tiny = SettlementCalculator.calculate(d("2.00"), null, new Rates(d("0"), d("5")));
        assertEquals(d("2.00"), tiny.otherFees());
        assertEquals(d("0.00"), tiny.net()); // never negative
    }

    @Test
    void refundsReduceEligibleRevenueAndNeverGoBelowZero() {
        var partial = SettlementCalculator.calculate(d("100"), d("30"), new Rates(d("0.10"), d("0")));
        assertEquals(d("70.00"), partial.eligibleRevenue());
        assertEquals(d("7.00"), partial.commission());
        assertEquals(d("63.00"), partial.net());
        var full = SettlementCalculator.calculate(d("100"), d("150"), NO_FEES);
        assertEquals(d("0.00"), full.eligibleRevenue());
        assertEquals(d("0.00"), full.net());
    }

    @Test
    void zeroSubtotalIsAllowedAndNegativeInputsAreRefused() {
        assertEquals(d("0.00"), SettlementCalculator.calculate(d("0"), null, NO_FEES).net());
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.calculate(d("-1"), null, NO_FEES));
        assertThrows(IllegalArgumentException.class, () -> new Rates(d("-0.1"), d("0")));
        assertThrows(IllegalArgumentException.class, () -> new Rates(d("1.5"), d("0")));
        assertThrows(IllegalArgumentException.class, () -> new Rates(d("0"), d("-1")));
    }

    @Test
    void commissionPlusFeesPlusNetAlwaysEqualsEligibleRevenue() {
        for (String sub : new String[] {"0.01", "9.99", "100.00", "1234.56"}) {
            for (String rate : new String[] {"0", "0.07", "0.125", "0.5", "1"}) {
                var r = SettlementCalculator.calculate(d(sub), null, new Rates(d(rate), d("1.50")));
                assertEquals(0, r.eligibleRevenue().compareTo(r.commission().add(r.otherFees()).add(r.net())),
                        sub + " @ " + rate);
                assertTrue(r.net().signum() >= 0);
            }
        }
    }
}
