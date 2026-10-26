package com.momocircle.bulk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Server-side, dependency-free pricing rules for bulk groups. All money is {@link BigDecimal} at 2 dp, HALF_UP.
 * Prices are PER PACK because the existing wholesale catalogue prices and orders in packs.
 * Tiers come from the supplier's configuration; this class never invents one.
 */
public final class BulkPricing {

    public record Tier(int minPacks, BigDecimal pricePerPack) {}

    private BulkPricing() {}

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Validates a supplier tier ladder against the base price: positive, strictly increasing thresholds, never above base. */
    public static List<Tier> validateLadder(BigDecimal basePrice, List<Tier> tiers) {
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(Tier::minPacks));
        int prevMin = 0;
        BigDecimal prevPrice = basePrice;
        for (Tier t : sorted) {
            if (t.minPacks() <= 0) throw new IllegalArgumentException("Tier threshold must be at least 1 pack");
            if (t.minPacks() <= prevMin) throw new IllegalArgumentException("Tier thresholds must be strictly increasing");
            if (t.pricePerPack() == null || t.pricePerPack().signum() <= 0) throw new IllegalArgumentException("Tier price must be positive");
            if (t.pricePerPack().compareTo(prevPrice) > 0) throw new IllegalArgumentException("A higher tier cannot cost more than a lower one");
            prevMin = t.minPacks();
            prevPrice = t.pricePerPack();
        }
        return sorted;
    }

    /** Price per pack at an aggregate quantity: the best tier whose threshold is met, otherwise the base price. */
    public static BigDecimal priceFor(BigDecimal basePrice, List<Tier> tiers, int aggregatePacks) {
        BigDecimal best = basePrice;
        int bestMin = 0;
        for (Tier t : tiers) {
            if (aggregatePacks >= t.minPacks() && t.minPacks() >= bestMin) {
                best = t.pricePerPack();
                bestMin = t.minPacks();
            }
        }
        return money(best);
    }

    public static BigDecimal discountPerPack(BigDecimal basePrice, BigDecimal appliedPrice) {
        return money(basePrice.subtract(appliedPrice).max(BigDecimal.ZERO));
    }

    public static BigDecimal lineTotal(BigDecimal pricePerPack, int packs) {
        return money(pricePerPack.multiply(BigDecimal.valueOf(packs)));
    }

    /** Next tier above the aggregate, for "add N more packs to unlock price X"; null when already at the top. */
    public static Tier nextTier(List<Tier> tiers, int aggregatePacks) {
        return tiers.stream().filter(t -> t.minPacks() > aggregatePacks)
                .min(Comparator.comparingInt(Tier::minPacks)).orElse(null);
    }
}
