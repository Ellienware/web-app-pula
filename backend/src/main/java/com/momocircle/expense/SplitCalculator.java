package com.momocircle.expense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic split arithmetic in integer minor units (cents). No floating point, no Spring, no I/O.
 *
 * <p>Every method returns one amount per participant, in the participant order given, and guarantees
 * {@code sum(result) == totalMinor} exactly or throws {@link SplitException}. Rounding rules (also shown to users):
 * <ul>
 *   <li><b>Equal</b>: everyone gets {@code floor(total / n)}; the leftover minor units go one each to the FIRST participants.</li>
 *   <li><b>Percentage</b>: percentages are integer basis points (1.00% = 100) summing to exactly 10,000. Each person gets
 *       {@code floor(total * bp / 10,000)}; the leftover minor units go one each to the largest fractional remainders, ties
 *       broken by participant order.</li>
 *   <li><b>Custom</b>: amounts are taken as given and must add up to the total exactly; nothing is rounded.</li>
 *   <li><b>Item-based</b>: each item is split equally among its assignees (leftover minor units to the first assignees in
 *       participant order); a participant's share is the sum of their parts. Item amounts must add up to the total exactly.</li>
 * </ul>
 */
public final class SplitCalculator {

    public static final int FULL_BASIS_POINTS = 10_000;

    private SplitCalculator() { }

    /** A validation failure with a stable code (the service maps it to HTTP 400). */
    public static final class SplitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public SplitException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** One line item: its amount and the participants (by id) who share it. */
    public record Item(String name, long amountMinor, List<String> assigneeIds) { }

    public static long[] equal(long totalMinor, int participants) {
        requirePositiveTotal(totalMinor);
        if (participants < 1) throw new SplitException("NO_PARTICIPANTS", "At least one participant is required");
        long base = totalMinor / participants;
        int extra = (int) (totalMinor - base * participants);
        long[] out = new long[participants];
        for (int i = 0; i < participants; i++) out[i] = base + (i < extra ? 1 : 0);
        return out;
    }

    public static long[] custom(long totalMinor, long[] amountsMinor) {
        requirePositiveTotal(totalMinor);
        if (amountsMinor.length < 1) throw new SplitException("NO_PARTICIPANTS", "At least one participant is required");
        long sum = 0;
        for (long a : amountsMinor) {
            if (a < 0) throw new SplitException("NEGATIVE_SHARE", "Split amounts cannot be negative");
            sum = Math.addExact(sum, a);
        }
        if (sum != totalMinor) {
            throw new SplitException("SHARES_DO_NOT_ADD_UP", "Split amounts (" + sum + " minor units) must equal the expense total ("
                    + totalMinor + " minor units)");
        }
        return amountsMinor.clone();
    }

    public static long[] percentage(long totalMinor, int[] basisPoints) {
        requirePositiveTotal(totalMinor);
        int n = basisPoints.length;
        if (n < 1) throw new SplitException("NO_PARTICIPANTS", "At least one participant is required");
        long bpSum = 0;
        for (int bp : basisPoints) {
            if (bp < 0) throw new SplitException("NEGATIVE_SHARE", "Percentages cannot be negative");
            bpSum += bp;
        }
        if (bpSum != FULL_BASIS_POINTS) {
            throw new SplitException("PERCENTAGES_DO_NOT_ADD_UP", "Percentages must add up to exactly 100.00 (got "
                    + bpSum / 100 + "." + String.format("%02d", bpSum % 100) + ")");
        }
        long[] out = new long[n];
        long[] remainder = new long[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            long product = Math.multiplyExact(totalMinor, (long) basisPoints[i]);
            out[i] = product / FULL_BASIS_POINTS;
            remainder[i] = product % FULL_BASIS_POINTS;
            assigned += out[i];
        }
        int leftover = (int) (totalMinor - assigned);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
        for (int k = 0; k < leftover; k++) out[order[k]]++;
        return out;
    }

    /**
     * @param participantIds participants in their fixed order; every assignee must be one of them
     * @return the share of each participant, in {@code participantIds} order
     */
    public static long[] items(long totalMinor, List<Item> items, List<String> participantIds) {
        requirePositiveTotal(totalMinor);
        if (participantIds.isEmpty()) throw new SplitException("NO_PARTICIPANTS", "At least one participant is required");
        if (items == null || items.isEmpty()) throw new SplitException("NO_ITEMS", "At least one item is required");
        Map<String, Integer> position = new LinkedHashMap<>();
        for (int i = 0; i < participantIds.size(); i++) position.put(participantIds.get(i), i);
        long[] out = new long[participantIds.size()];
        long itemSum = 0;
        for (Item item : items) {
            if (item.amountMinor() <= 0) throw new SplitException("INVALID_ITEM", "Every item needs an amount above zero");
            if (item.assigneeIds() == null || item.assigneeIds().isEmpty()) {
                throw new SplitException("ITEM_UNASSIGNED", "Item \"" + item.name() + "\" has nobody assigned to it");
            }
            List<Integer> idx = new ArrayList<>();
            for (String id : item.assigneeIds()) {
                Integer p = position.get(id);
                if (p == null) throw new SplitException("ITEM_ASSIGNEE_NOT_PARTICIPANT", "Item \"" + item.name() + "\" is assigned to someone who is not a participant");
                if (!idx.contains(p)) idx.add(p);
            }
            idx.sort(Integer::compare); // participant order, so the result never depends on the order the client listed assignees
            long base = item.amountMinor() / idx.size();
            int extra = (int) (item.amountMinor() - base * idx.size());
            for (int k = 0; k < idx.size(); k++) out[idx.get(k)] += base + (k < extra ? 1 : 0);
            itemSum = Math.addExact(itemSum, item.amountMinor());
        }
        if (itemSum != totalMinor) {
            throw new SplitException("ITEMS_DO_NOT_ADD_UP", "Items (" + itemSum + " minor units) must equal the expense total ("
                    + totalMinor + " minor units)");
        }
        return out;
    }

    public static long sum(long[] amounts) {
        long s = 0;
        for (long a : amounts) s = Math.addExact(s, a);
        return s;
    }

    private static void requirePositiveTotal(long totalMinor) {
        if (totalMinor <= 0) throw new SplitException("INVALID_TOTAL", "Expense amount must be positive");
    }
}
