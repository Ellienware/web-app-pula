package com.momocircle.branch;

import java.math.BigDecimal;

/**
 * Pure integer arithmetic for branch stock. Stock is conserved: central + sum(branches) + units held by open orders never changes
 * except through a sale. Every operation validates BEFORE returning new balances, so a caller can apply both sides atomically.
 */
public final class BranchStockMath {

    public record Pair(int from, int to) { }

    private BranchStockMath() { }

    private static void requirePositive(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be at least 1");
    }

    /** Central pool -> branch. Returns (newCentral, newBranch). */
    public static Pair allocate(int central, int branch, int qty) {
        requirePositive(qty);
        if (central < 0 || branch < 0) throw new IllegalStateException("Negative stock");
        if (qty > central) throw new IllegalArgumentException("Not enough unallocated stock (" + central + " available)");
        return new Pair(central - qty, Math.addExact(branch, qty));
    }

    /** Branch -> central pool. Returns (newBranch, newCentral). */
    public static Pair returnToCentral(int branch, int central, int qty) {
        requirePositive(qty);
        if (qty > branch) throw new IllegalArgumentException("The branch only has " + branch + " in stock");
        return new Pair(branch - qty, Math.addExact(central, qty));
    }

    /** Branch -> branch. Returns (newSource, newDestination). */
    public static Pair transfer(int source, int destination, int qty) {
        requirePositive(qty);
        if (qty > source) throw new IllegalArgumentException("The source branch only has " + source + " in stock");
        return new Pair(source - qty, Math.addExact(destination, qty));
    }

    /** A sale reservation. Returns the new branch quantity. */
    public static int reserve(int branch, boolean available, int qty) {
        requirePositive(qty);
        if (!available) throw new IllegalArgumentException("This item is not available at this branch");
        if (qty > branch) throw new IllegalArgumentException("Not enough stock at this branch");
        return branch - qty;
    }

    /** Compensation for a failed/cancelled order. Returns the new branch quantity. */
    public static int release(int branch, int qty) {
        requirePositive(qty);
        return Math.addExact(branch, qty);
    }

    /** Branch price: a positive override wins, otherwise the catalogue price. */
    public static BigDecimal unitPrice(BigDecimal catalogue, BigDecimal override) {
        if (override != null) {
            if (override.signum() <= 0) throw new IllegalArgumentException("Branch price must be greater than zero");
            return override;
        }
        return catalogue;
    }
}
