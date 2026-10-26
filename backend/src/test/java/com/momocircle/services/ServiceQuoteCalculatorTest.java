package com.momocircle.services;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.ServicePricingModel;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ServiceQuoteCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static ServiceQuoteCalculator.Result calc(ServicePricingModel m, String price, String hours, String labour,
                                                      String delivery, String other, String otherDesc, String materials) {
        return ServiceQuoteCalculator.compute(m, price == null ? null : d(price), hours == null ? null : d(hours),
                labour == null ? null : d(labour), delivery == null ? null : d(delivery), other == null ? null : d(other),
                otherDesc, materials == null ? null : d(materials));
    }

    @Test
    void fixedPriceLabourIsTheListedPriceAndTotalIsTheSumOfParts() {
        var r = calc(ServicePricingModel.FIXED, "450.00", null, null, "50.00", "20.50", "call-out", "129.99");
        assertEquals(d("450.00"), r.labour());
        assertEquals(d("650.49"), r.total());
        assertNull(r.hours());
    }

    @Test
    void fixedPriceCannotBeQuotedAtADifferentAmount() {
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.FIXED, "450.00", null, "400.00", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.FIXED, "450.00", null, "450.01", null, null, null, null));
        assertDoesNotThrow(() -> calc(ServicePricingModel.FIXED, "450.00", null, "450.00", null, null, null, null));
    }

    @Test
    void hourlyLabourIsRateTimesHoursComputedByTheServer() {
        var r = calc(ServicePricingModel.HOURLY, "120.00", "2.50", null, null, null, null, null);
        assertEquals(d("300.00"), r.labour());
        assertEquals(d("2.50"), r.hours());
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.HOURLY, "120.00", "2.50", "100.00", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.HOURLY, "120.00", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.HOURLY, "120.00", "0", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.HOURLY, "120.00", "1001", null, null, null, null, null));
    }

    @Test
    void startingFromCannotGoBelowTheListedStartingPrice() {
        assertEquals(d("800.00"), calc(ServicePricingModel.STARTING_FROM, "500.00", null, "800.00", null, null, null, null).total());
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.STARTING_FROM, "500.00", null, "499.99", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.STARTING_FROM, "500.00", null, null, null, null, null, null));
    }

    @Test
    void quoteRequiredTakesProviderLabourButStillValidatesIt() {
        assertEquals(d("1000.00"), calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "1000.00", null, null, null, null).total());
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "-1.00", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "0.00", null, null, null, null)); // total must be > 0
    }

    @Test
    void otherAmountNeedsADescriptionAndNegativesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "100.00", null, "10.00", " ", null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "100.00", "-1", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "100.00", null, "-1", "x", null));
    }

    @Test
    void amountsAreRoundedToTwoDecimalsWithoutFloatingPoint() {
        var r = calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "100.005", "0.1", "0.2", "x", "0.3");
        assertEquals(d("100.01"), r.labour());
        assertEquals(d("100.61"), r.total());   // 100.01 + 0.30 + 0.10 + 0.20, exact
        var h = calc(ServicePricingModel.HOURLY, "33.33", "3.00", null, null, null, null, null);
        assertEquals(d("99.99"), h.total());
    }

    @Test
    void absurdTotalsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "10000001.00", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calc(ServicePricingModel.QUOTE_REQUIRED, null, null, "9000000.00", "9000000.00", null, null, null));
    }
}
