package com.momocircle.future;

import com.momocircle.future.MerchantIncomeBasis.Input;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MerchantIncomeBasisTest {
    @Test void basisIsPaidNetAndPendingIsExcluded() {
        var r = MerchantIncomeBasis.of(new Input(90_000, 100_000, 0, 25_000, 3, 2));
        assertEquals(90_000, r.basisMinor()); assertEquals(25_000, r.pendingExcludedMinor()); assertEquals(10_000, r.feesAndCommissionMinor());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("pending")));
    }
    @Test void refundsAreAlreadyInsideTheNetAndNotSubtractedTwice() {
        var r = MerchantIncomeBasis.of(new Input(45_000, 100_000, 50_000, 0, 1, 0));
        assertEquals(45_000, r.basisMinor()); assertEquals(5_000, r.feesAndCommissionMinor());
    }
    @Test void emptyPeriodSaysSo() {
        var r = MerchantIncomeBasis.of(new Input(0, 0, 0, 0, 0, 0));
        assertEquals(0, r.basisMinor()); assertTrue(r.notes().stream().anyMatch(n -> n.contains("No paid")));
    }
    @Test void negativeInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MerchantIncomeBasis.of(new Input(-1, 0, 0, 0, 0, 0)));
    }
}
