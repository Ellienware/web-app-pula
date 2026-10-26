package com.momocircle.stokvel;

import static com.momocircle.stokvel.ContributionState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContributionStateTest {

    private static final BigDecimal R500 = new BigDecimal("500.00");

    @Test
    void paidWhenExpectedIsMetOrExceeded() {
        assertEquals(PAID, ContributionState.of(R500, new BigDecimal("500.00"), false));
        assertEquals(PAID, ContributionState.of(R500, new BigDecimal("650.00"), true));
    }

    @Test
    void duePartialAndMissed() {
        assertEquals(DUE, ContributionState.of(R500, BigDecimal.ZERO, false));
        assertEquals(PARTIAL, ContributionState.of(R500, new BigDecimal("200.00"), false));
        assertEquals(MISSED, ContributionState.of(R500, BigDecimal.ZERO, true));
        assertEquals(MISSED, ContributionState.of(R500, new BigDecimal("200.00"), true));
    }

    @Test
    void noExpectedAmountMeansNothingCanBeMissed() {
        assertEquals(DUE, ContributionState.of(null, BigDecimal.ZERO, true));
        assertEquals(DUE, ContributionState.of(BigDecimal.ZERO, BigDecimal.ZERO, true));
    }
}
