package com.momocircle.branch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BranchStockMathTest {

    @Test
    void allocationConservesTotalStock() {
        var p = BranchStockMath.allocate(100, 5, 30);
        assertEquals(70, p.from());
        assertEquals(35, p.to());
        assertEquals(105, p.from() + p.to());
    }

    @Test
    void cannotAllocateMoreThanTheCentralPoolHolds() {
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.allocate(10, 0, 11));
    }

    @Test
    void transferMovesStockBetweenBranchesWithoutCreatingOrLosingAny() {
        var p = BranchStockMath.transfer(40, 10, 15);
        assertEquals(25, p.from());
        assertEquals(25, p.to());
        assertEquals(50, p.from() + p.to());
    }

    @Test
    void cannotTransferMoreThanTheSourceHolds() {
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.transfer(3, 0, 4));
    }

    @Test
    void quantitiesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.transfer(5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.allocate(5, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.reserve(5, true, 0));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.release(5, 0));
    }

    @Test
    void reserveNeverGoesNegativeAndRespectsAvailability() {
        assertEquals(2, BranchStockMath.reserve(5, true, 3));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.reserve(2, true, 3));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.reserve(50, false, 1));
    }

    @Test
    void reserveThenReleaseRestoresTheBalance() {
        int after = BranchStockMath.reserve(8, true, 5);
        assertEquals(8, BranchStockMath.release(after, 5));
    }

    @Test
    void returnToCentralCannotExceedBranchStock() {
        var p = BranchStockMath.returnToCentral(10, 0, 10);
        assertEquals(0, p.from());
        assertEquals(10, p.to());
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.returnToCentral(2, 0, 3));
    }

    @Test
    void branchPriceOverrideWinsAndMustBePositive() {
        assertEquals(new BigDecimal("12.50"), BranchStockMath.unitPrice(new BigDecimal("10.00"), new BigDecimal("12.50")));
        assertEquals(new BigDecimal("10.00"), BranchStockMath.unitPrice(new BigDecimal("10.00"), null));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.unitPrice(new BigDecimal("10.00"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BranchStockMath.unitPrice(new BigDecimal("10.00"), new BigDecimal("-1")));
    }
}
