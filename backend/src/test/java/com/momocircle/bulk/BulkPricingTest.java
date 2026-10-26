package com.momocircle.bulk;

import com.momocircle.bulk.BulkPricing.Tier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BulkPricingTest {

    private static final BigDecimal BASE = new BigDecimal("100.00");
    private final List<Tier> ladder = List.of(new Tier(100, new BigDecimal("90.00")), new Tier(200, new BigDecimal("80.00")));

    @Test
    void belowFirstTierUsesBasePrice() {
        assertEquals(new BigDecimal("100.00"), BulkPricing.priceFor(BASE, ladder, 99));
    }

    @Test
    void tierAppliesExactlyAtThreshold() {
        assertEquals(new BigDecimal("90.00"), BulkPricing.priceFor(BASE, ladder, 100));
        assertEquals(new BigDecimal("80.00"), BulkPricing.priceFor(BASE, ladder, 200));
        assertEquals(new BigDecimal("80.00"), BulkPricing.priceFor(BASE, ladder, 5000));
    }

    @Test
    void noTiersMeansNoInventedDiscount() {
        assertEquals(new BigDecimal("100.00"), BulkPricing.priceFor(BASE, List.of(), 100000));
    }

    @Test
    void unsortedTiersStillPickBestApplicable() {
        List<Tier> shuffled = List.of(new Tier(200, new BigDecimal("80.00")), new Tier(100, new BigDecimal("90.00")));
        assertEquals(new BigDecimal("90.00"), BulkPricing.priceFor(BASE, shuffled, 150));
    }

    @Test
    void discountAndLineTotalArePreciseTwoDecimals() {
        assertEquals(new BigDecimal("10.00"), BulkPricing.discountPerPack(BASE, new BigDecimal("90.00")));
        assertEquals(new BigDecimal("0.00"), BulkPricing.discountPerPack(BASE, BASE));
        assertEquals(new BigDecimal("2700.00"), BulkPricing.lineTotal(new BigDecimal("90.00"), 30));
        // rounding: 3 packs at 33.335 -> 100.005 -> HALF_UP -> 100.01
        assertEquals(new BigDecimal("100.01"), BulkPricing.lineTotal(new BigDecimal("33.335"), 3));
    }

    @Test
    void perBusinessTotalsSumToCombinedTotalWhenPriceHasTwoDecimals() {
        BigDecimal price = BulkPricing.priceFor(BASE, ladder, 100);
        BigDecimal sum = BulkPricing.lineTotal(price, 20).add(BulkPricing.lineTotal(price, 30)).add(BulkPricing.lineTotal(price, 50));
        assertEquals(BulkPricing.lineTotal(price, 100), sum);
    }

    @Test
    void ladderValidationRejectsBadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> BulkPricing.validateLadder(BASE, List.of(new Tier(0, new BigDecimal("90")))));
        assertThrows(IllegalArgumentException.class, () -> BulkPricing.validateLadder(BASE,
                List.of(new Tier(100, new BigDecimal("90")), new Tier(100, new BigDecimal("80")))));
        assertThrows(IllegalArgumentException.class, () -> BulkPricing.validateLadder(BASE, List.of(new Tier(100, new BigDecimal("101")))));
        assertThrows(IllegalArgumentException.class, () -> BulkPricing.validateLadder(BASE,
                List.of(new Tier(100, new BigDecimal("80")), new Tier(200, new BigDecimal("90")))));
        assertEquals(2, BulkPricing.validateLadder(BASE, ladder).size());
    }

    @Test
    void nextTierIsTheNearestUnmet() {
        assertEquals(100, BulkPricing.nextTier(ladder, 20).minPacks());
        assertEquals(200, BulkPricing.nextTier(ladder, 100).minPacks());
        assertNull(BulkPricing.nextTier(ladder, 200));
    }
}
