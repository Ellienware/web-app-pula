package com.momocircle.expense;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.expense.SplitCalculator.Item;
import com.momocircle.expense.SplitCalculator.SplitException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Pure unit tests (no Spring, no database) for the minor-unit split arithmetic. */
class SplitCalculatorTest {

    private static String code(Runnable r) { return assertThrows(SplitException.class, r::run).code(); }

    @Test void equalSplitGivesLeftoverCentsToTheFirstParticipants() {
        assertArrayEquals(new long[] {3334, 3333, 3333}, SplitCalculator.equal(10000, 3));
        assertArrayEquals(new long[] {1, 0, 0}, SplitCalculator.equal(1, 3));
        assertArrayEquals(new long[] {4, 3}, SplitCalculator.equal(7, 2));
        assertArrayEquals(new long[] {3000, 3000, 3000}, SplitCalculator.equal(9000, 3));
        assertArrayEquals(new long[] {555}, SplitCalculator.equal(555, 1));
    }

    @Test void equalRejectsNonPositiveTotalsAndNoParticipants() {
        assertEquals("INVALID_TOTAL", code(() -> SplitCalculator.equal(0, 2)));
        assertEquals("NO_PARTICIPANTS", code(() -> SplitCalculator.equal(10, 0)));
    }

    @Test void customMustAddUpExactlyAndNotBeNegative() {
        assertArrayEquals(new long[] {7000, 3000}, SplitCalculator.custom(10000, new long[] {7000, 3000}));
        assertEquals("SHARES_DO_NOT_ADD_UP", code(() -> SplitCalculator.custom(10000, new long[] {7000, 2000})));
        assertEquals("NEGATIVE_SHARE", code(() -> SplitCalculator.custom(10000, new long[] {11000, -1000})));
    }

    @Test void percentageUsesLargestRemainderWithDeterministicTieBreak() {
        assertArrayEquals(new long[] {5001, 5000}, SplitCalculator.percentage(10001, new int[] {5000, 5000}));
        assertArrayEquals(new long[] {3334, 3333, 3333}, SplitCalculator.percentage(10000, new int[] {3334, 3333, 3333}));
        assertArrayEquals(new long[] {1, 0, 0}, SplitCalculator.percentage(1, new int[] {3334, 3333, 3333}));
        assertArrayEquals(new long[] {1, 0}, SplitCalculator.percentage(1, new int[] {5000, 5000}));
        assertArrayEquals(new long[] {666, 666, 667}, SplitCalculator.percentage(1999, new int[] {3333, 3333, 3334}));
    }

    @Test void percentagesMustTotalExactly100() {
        assertEquals("PERCENTAGES_DO_NOT_ADD_UP", code(() -> SplitCalculator.percentage(100, new int[] {5000, 4999})));
        assertEquals("PERCENTAGES_DO_NOT_ADD_UP", code(() -> SplitCalculator.percentage(100, new int[] {5000, 5001})));
    }

    @Test void itemBasedSplitsEachItemAmongItsAssignees() {
        long[] r = SplitCalculator.items(10000, List.of(new Item("pizza", 6000, List.of("a", "b")), new Item("wine", 4000, List.of("b"))),
                List.of("a", "b"));
        assertArrayEquals(new long[] {3000, 7000}, r);
        // the order the client lists assignees in does not change the result
        assertArrayEquals(new long[] {3334, 3333, 3333},
                SplitCalculator.items(10000, List.of(new Item("x", 10000, List.of("c", "a", "b"))), List.of("a", "b", "c")));
    }

    @Test void itemBasedValidatesItems() {
        assertEquals("ITEMS_DO_NOT_ADD_UP", code(() -> SplitCalculator.items(10000, List.of(new Item("x", 9000, List.of("a"))), List.of("a"))));
        assertEquals("ITEM_UNASSIGNED", code(() -> SplitCalculator.items(100, List.of(new Item("x", 100, List.of())), List.of("a"))));
        assertEquals("ITEM_ASSIGNEE_NOT_PARTICIPANT", code(() -> SplitCalculator.items(100, List.of(new Item("x", 100, List.of("z"))), List.of("a"))));
        assertEquals("INVALID_ITEM", code(() -> SplitCalculator.items(100, List.of(new Item("x", 0, List.of("a"))), List.of("a"))));
    }

    @Test void sumsAlwaysEqualTheTotalAndPercentagesStayWithinOneMinorUnit() {
        Random r = new Random(42);
        for (int i = 0; i < 20000; i++) {
            long total = 1 + r.nextInt(5_000_000);
            int n = 1 + r.nextInt(12);
            assertEquals(total, SplitCalculator.sum(SplitCalculator.equal(total, n)));
            int[] bp = new int[n];
            int left = 10000;
            for (int k = 0; k < n - 1; k++) { bp[k] = r.nextInt(left + 1); left -= bp[k]; }
            bp[n - 1] = left;
            long[] p = SplitCalculator.percentage(total, bp);
            assertEquals(total, SplitCalculator.sum(p));
            for (int k = 0; k < n; k++) {
                long floor = total * bp[k] / 10000;
                assertTrue(p[k] == floor || p[k] == floor + 1);
            }
            assertArrayEquals(p, SplitCalculator.percentage(total, bp), Arrays.toString(bp) + " must be deterministic");
        }
    }
}
