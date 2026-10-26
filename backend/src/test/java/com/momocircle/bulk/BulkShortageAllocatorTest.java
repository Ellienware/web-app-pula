package com.momocircle.bulk;

import com.momocircle.bulk.BulkShortageAllocator.Claim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BulkShortageAllocatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static int sum(Map<String, Integer> m) { return m.values().stream().mapToInt(Integer::intValue).sum(); }

    @Test
    void noShortageGivesEveryoneTheirOrder() {
        Map<String, Integer> r = BulkShortageAllocator.allocate(100, List.of(new Claim("a", 20, T0), new Claim("b", 30, T0), new Claim("c", 50, T0)));
        assertEquals(20, r.get("a")); assertEquals(30, r.get("b")); assertEquals(50, r.get("c"));
    }

    @Test
    void proRataSplitIsExactWhenDivisible() {
        Map<String, Integer> r = BulkShortageAllocator.allocate(80, List.of(new Claim("a", 20, T0), new Claim("b", 30, T0), new Claim("c", 50, T0)));
        assertEquals(16, r.get("a")); assertEquals(24, r.get("b")); assertEquals(40, r.get("c"));
    }

    @Test
    void totalAllocatedAlwaysEqualsAvailableAndNobodyGetsMoreThanOrdered() {
        for (int available = 0; available <= 100; available++) {
            Map<String, Integer> r = BulkShortageAllocator.allocate(available,
                    List.of(new Claim("a", 7, T0), new Claim("b", 33, T0.plusSeconds(1)), new Claim("c", 60, T0.plusSeconds(2))));
            assertEquals(available, sum(r), "available=" + available);
            assertTrue(r.get("a") <= 7 && r.get("b") <= 33 && r.get("c") <= 60);
        }
    }

    @Test
    void remainderTiesGoToEarliestConfirmation() {
        // 1 pack, three equal orders: all remainders tie, the earliest confirmation gets it.
        Map<String, Integer> r = BulkShortageAllocator.allocate(1,
                List.of(new Claim("late", 10, T0.plusSeconds(60)), new Claim("early", 10, T0), new Claim("mid", 10, T0.plusSeconds(30))));
        assertEquals(1, r.get("early")); assertEquals(0, r.get("mid")); assertEquals(0, r.get("late"));
    }

    @Test
    void resultIsDeterministicRegardlessOfInputOrder() {
        List<Claim> one = List.of(new Claim("a", 10, T0), new Claim("b", 10, T0.plusSeconds(1)), new Claim("c", 10, T0.plusSeconds(2)));
        List<Claim> two = List.of(one.get(2), one.get(0), one.get(1));
        assertEquals(BulkShortageAllocator.allocate(10, one).get("a"), BulkShortageAllocator.allocate(10, two).get("a"));
        assertEquals(BulkShortageAllocator.allocate(10, one).get("c"), BulkShortageAllocator.allocate(10, two).get("c"));
    }

    @Test
    void zeroAvailableAllocatesNothing() {
        Map<String, Integer> r = BulkShortageAllocator.allocate(0, List.of(new Claim("a", 5, T0), new Claim("b", 5, T0)));
        assertEquals(0, sum(r));
    }
}
