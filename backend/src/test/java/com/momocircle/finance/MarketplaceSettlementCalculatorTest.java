package com.momocircle.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;
import com.momocircle.finance.MarketplaceSettlementCalculator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketplaceSettlementCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static Rates rates(String commission, String fixed, String pfRate, String pfFixed) {
        return new Rates(d(commission), d(fixed), d(pfRate), d(pfFixed));
    }

    @Test
    void grossLessRefundsThenCommissionFixedFeePaymentFeeAndAdjustments() {
        Result r = MarketplaceSettlementCalculator.calculate(d("1000.00"), d("100.00"), d("-25.00"), rates("0.10", "2.00", "0.015", "0.50"));
        assertEquals(d("900.00"), r.eligible());
        assertEquals(d("90.00"), r.commission());           // 10% of 900
        assertEquals(d("2.00"), r.fixedFee());
        assertEquals(d("14.00"), r.paymentFee());           // 900 x 1.5% = 13.50 + 0.50
        assertEquals(d("-25.00"), r.adjustments());
        assertEquals(d("769.00"), r.net());                 // 900 - 90 - 2 - 14 - 25
    }

    @Test
    void zeroRatesPayTheSellerEverything() {
        Result r = MarketplaceSettlementCalculator.calculate(d("250.00"), BigDecimal.ZERO, BigDecimal.ZERO, rates("0", "0", "0", "0"));
        assertEquals(d("250.00"), r.net());
        assertEquals(d("0.00"), r.commission());
    }

    @Test
    void feesCanNeverPushTheNetBelowZeroOrExceedTheRevenue() {
        Result r = MarketplaceSettlementCalculator.calculate(d("10.00"), BigDecimal.ZERO, BigDecimal.ZERO, rates("0.10", "50.00", "0.03", "5.00"));
        assertEquals(d("1.00"), r.commission());
        assertEquals(d("9.00"), r.fixedFee());              // capped at what is left after commission
        assertEquals(d("0.00"), r.paymentFee());            // nothing left
        assertEquals(d("0.00"), r.net());
    }

    @Test
    void fullRefundLeavesNothingAndANegativeAdjustmentCannotMakeItNegative() {
        Result full = MarketplaceSettlementCalculator.calculate(d("100.00"), d("100.00"), BigDecimal.ZERO, rates("0.10", "1.00", "0", "0"));
        assertEquals(d("0.00"), full.net());
        assertEquals(d("0.00"), full.commission());
        Result over = MarketplaceSettlementCalculator.calculate(d("100.00"), BigDecimal.ZERO, d("-500.00"), rates("0.10", "0", "0", "0"));
        assertEquals(d("0.00"), over.net());
        Result moreRefundThanSale = MarketplaceSettlementCalculator.calculate(d("100.00"), d("150.00"), BigDecimal.ZERO, rates("0.10", "0", "0", "0"));
        assertEquals(d("0.00"), moreRefundThanSale.eligible());
    }

    @Test
    void creditsRaiseTheNetAndEverythingIsExactBigDecimal() {
        Result r = MarketplaceSettlementCalculator.calculate(d("0.10"), BigDecimal.ZERO, d("0.20"), rates("0", "0", "0", "0"));
        assertEquals(d("0.30"), r.net());                   // 0.1 + 0.2 is exactly 0.30, no float error
        Result rounded = MarketplaceSettlementCalculator.calculate(d("33.33"), BigDecimal.ZERO, BigDecimal.ZERO, rates("0.075", "0", "0", "0"));
        assertEquals(d("2.50"), rounded.commission());      // 2.49975 -> 2.50 (HALF_UP)
        assertEquals(d("30.83"), rounded.net());
        assertEquals(2, rounded.net().scale());
    }

    @Test
    void invalidInputsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MarketplaceSettlementCalculator.calculate(d("-1.00"), null, null, rates("0", "0", "0", "0")));
        assertThrows(IllegalArgumentException.class, () -> rates("1.01", "0", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> rates("-0.01", "0", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> rates("0", "-1", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> rates("0", "0", "2", "0"));
    }

    /** Food must not change: with no payment fee and no adjustments the shared formula equals the old food calculator. */
    @Test
    void foodResultsAreUnchangedByTheSharedFormula() {
        String[] subtotals = {"0.00", "0.01", "9.99", "49.50", "100.00", "123.45", "999.99", "1500.00", "33333.33"};
        String[] refunds = {"0.00", "0.01", "10.00", "49.50", "100.00", "500.00"};
        String[] commissions = {"0", "0.05", "0.075", "0.10", "0.125", "0.2", "1"};
        String[] fixed = {"0.00", "0.50", "2.00", "15.00"};
        int compared = 0;
        for (String s : subtotals) for (String rf : refunds) for (String c : commissions) for (String f : fixed) {
            SettlementCalculator.Result old = SettlementCalculator.calculate(d(s), d(rf), new SettlementCalculator.Rates(d(c), d(f)));
            Result now = MarketplaceSettlementCalculator.calculate(d(s), d(rf), BigDecimal.ZERO, Rates.of(d(c), d(f)));
            String ctx = s + "/" + rf + "/" + c + "/" + f;
            assertEquals(old.foodSubtotal(), now.gross(), ctx);
            assertEquals(old.refundedAmount(), now.refunded(), ctx);
            assertEquals(old.eligibleRevenue(), now.eligible(), ctx);
            assertEquals(old.commission(), now.commission(), ctx);
            assertEquals(old.otherFees(), now.fixedFee(), ctx);
            assertEquals(old.net(), now.net(), ctx);
            compared++;
        }
        assertTrue(compared > 1000);
    }
}
