package com.momocircle.bulk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documented shortage policy: PRO-RATA by ordered quantity, whole packs, largest-remainder rounding.
 * <ol>
 *   <li>Each business is entitled to floor(available * ordered / totalOrdered) packs.</li>
 *   <li>Leftover packs go to the largest fractional remainders.</li>
 *   <li>Remainder ties are broken by the EARLIEST confirmation time, then by id (deterministic).</li>
 *   <li>No business ever receives more than it ordered.</li>
 * </ol>
 * Deterministic, so the same inputs always give the same split, and no business is arbitrarily picked to absorb a shortage.
 */
public final class BulkShortageAllocator {

    public record Claim(String id, int orderedPacks, Instant confirmedAt) {}

    private BulkShortageAllocator() {}

    /** @return id -> packs actually allocated (sum == min(available, total ordered)). */
    public static Map<String, Integer> allocate(int availablePacks, List<Claim> claims) {
        if (availablePacks < 0) throw new IllegalArgumentException("available packs cannot be negative");
        long total = claims.stream().mapToLong(Claim::orderedPacks).sum();
        Map<String, Integer> result = new LinkedHashMap<>();
        if (total == 0) {
            claims.forEach(c -> result.put(c.id(), 0));
            return result;
        }
        if (availablePacks >= total) {
            claims.forEach(c -> result.put(c.id(), c.orderedPacks()));
            return result;
        }
        record Share(Claim claim, long floor, long remainderNumerator) {}
        List<Share> shares = new ArrayList<>();
        long assigned = 0;
        for (Claim c : claims) {
            long numerator = (long) availablePacks * c.orderedPacks();
            long floor = numerator / total;
            shares.add(new Share(c, floor, numerator % total));
            assigned += floor;
        }
        long leftover = availablePacks - assigned;
        List<Share> byRemainder = new ArrayList<>(shares);
        byRemainder.sort(Comparator
                .comparingLong(Share::remainderNumerator).reversed()
                .thenComparing(s -> s.claim().confirmedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.claim().id()));
        Map<String, Long> extra = new LinkedHashMap<>();
        for (int i = 0; i < leftover; i++) {
            Share s = byRemainder.get(i % byRemainder.size());
            extra.merge(s.claim().id(), 1L, Long::sum);
        }
        for (Share s : shares) {
            long give = Math.min(s.floor() + extra.getOrDefault(s.claim().id(), 0L), s.claim().orderedPacks());
            result.put(s.claim().id(), (int) give);
        }
        return result;
    }
}
