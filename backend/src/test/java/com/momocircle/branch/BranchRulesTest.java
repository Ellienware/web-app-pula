package com.momocircle.branch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchRulesTest {

    @Test
    void codesAreNormalisedConsistently() {
        assertEquals("PTA-CBD", BranchRules.requireValidCode("  pta cbd "));
        assertEquals("CEN_01", BranchRules.requireValidCode("cen_01"));
        assertNull(BranchRules.requireValidCode("   "));
        assertNull(BranchRules.requireValidCode(null));
    }

    @Test
    void badCodesAreRejected() {
        for (String bad : new String[]{"A", "-ab", "ab$", "x".repeat(21), "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidCode(bad), bad);
        }
    }

    @Test
    void nameIsRequiredAndBounded() {
        assertEquals("Example — Hatfield", BranchRules.requireValidName("  Example — Hatfield "));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidName("  "));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidName(null));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidName("x".repeat(256)));
    }

    @Test
    void coordinatesAreRangeCheckedAndComeInPairs() {
        BranchRules.requireValidCoordinates(null, null);
        BranchRules.requireValidCoordinates(new BigDecimal("-25.7479"), new BigDecimal("28.2293"));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidCoordinates(new BigDecimal("1"), null));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidCoordinates(new BigDecimal("91"), new BigDecimal("0")));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.requireValidCoordinates(new BigDecimal("0"), new BigDecimal("-181")));
    }

    @Test
    void optionalContactFieldsMayBeOmittedButMustBeValidWhenGiven() {
        assertNull(BranchRules.optionalPhone(null));
        assertNull(BranchRules.optionalEmail("  "));
        assertNull(BranchRules.optionalPostalCode(""));
        assertEquals("012 345 6789", BranchRules.optionalPhone("012 345 6789"));
        assertEquals("shop@example.com", BranchRules.optionalEmail("shop@example.com"));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.optionalPhone("abc"));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.optionalEmail("not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> BranchRules.optionalPostalCode("<script>"));
    }

    @Test
    void statusTransitionsAreOneWayToClosed() {
        assertTrue(BranchStatus.canTransition(BranchStatus.ACTIVE, BranchStatus.SUSPENDED));
        assertTrue(BranchStatus.canTransition(BranchStatus.SUSPENDED, BranchStatus.ACTIVE));
        assertTrue(BranchStatus.canTransition(BranchStatus.SUSPENDED, BranchStatus.CLOSED));
        assertFalse(BranchStatus.canTransition(BranchStatus.CLOSED, BranchStatus.ACTIVE));
        assertFalse(BranchStatus.canTransition(BranchStatus.ACTIVE, BranchStatus.ACTIVE));
        assertTrue(BranchStatus.ACTIVE.acceptsOrders());
        assertFalse(BranchStatus.SUSPENDED.acceptsOrders());
        assertFalse(BranchStatus.CLOSED.acceptsOrders());
    }
}
