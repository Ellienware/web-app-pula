package com.momocircle.future;

import com.momocircle.future.ProjectionCalculator.Input;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionCalculatorTest {
    private static final BigDecimal Z = BigDecimal.ZERO;
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test void zeroReturnIsContributionsOnly() {
        var r = ProjectionCalculator.project(new Input(0, 100_000, 1, Z, Z, Z, Z));
        assertEquals(1_200_000, r.futureValueMinor()); assertEquals(0, r.growthMinor());
    }
    @Test void lumpSum8Percent10Years() { // independent: 10000 * 1.08^10 = 21589.25
        assertEquals(2_158_925, ProjectionCalculator.project(new Input(1_000_000, 0, 10, d("8"), Z, Z, Z)).futureValueMinor());
    }
    @Test void feeIsSubtractedFromReturn() {
        assertEquals(2_158_925, ProjectionCalculator.project(new Input(1_000_000, 0, 10, d("10"), Z, d("2"), Z)).futureValueMinor());
    }
    @Test void inflationAdjustedValue() { // independent: 21589.25 / 1.05^10 = 13253.93
        assertEquals(1_325_393, ProjectionCalculator.project(new Input(1_000_000, 0, 10, d("8"), d("5"), Z, Z)).futureValueRealMinor());
    }
    @Test void annuityEndOfMonth12PercentEffective() { // independent: 12646.50
        assertEquals(1_264_650, ProjectionCalculator.project(new Input(0, 100_000, 1, d("12"), Z, Z, Z)).futureValueMinor());
    }
    @Test void rejectsUnrealisticInputs() {
        assertThrows(IllegalArgumentException.class, () -> ProjectionCalculator.project(new Input(0, 0, 0, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> ProjectionCalculator.project(new Input(0, 0, 5, d("90"), null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> ProjectionCalculator.project(new Input(0, 0, 5, null, d("-1"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> ProjectionCalculator.project(new Input(-1, 0, 5, null, null, null, null)));
    }
}
