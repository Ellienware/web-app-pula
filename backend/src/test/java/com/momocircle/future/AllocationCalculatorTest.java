package com.momocircle.future;

import com.momocircle.future.AllocationCalculator.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AllocationCalculatorTest {
    private static Line pct(String id, int bp) { return new Line(id, id, LineType.PERCENT, 0, bp); }
    private static Line fixed(String id, long m) { return new Line(id, id, LineType.FIXED, m, 0); }

    @Test void specExample_R10000_10_5_10_plus_R1000() {
        var r = AllocationCalculator.calculate(new Input(1_000_000, 0, 0, 0,
                List.of(pct("ef", 1000), pct("ed", 500), pct("rt", 1000), fixed("bz", 100_000))));
        assertTrue(r.valid());
        assertEquals(350_000, r.totalAllocatedMinor());
        assertEquals(650_000, r.remainingMinor());
    }
    @Test void roundsHalfUpPerLine() {
        assertEquals(3333, AllocationCalculator.calculate(new Input(10_001, 0, 0, 0, List.of(pct("g", 3333)))).lines().get(0).allocatedMinor());
        assertEquals(5003, AllocationCalculator.calculate(new Input(10_005, 0, 0, 0, List.of(pct("g", 5000)))).lines().get(0).allocatedMinor());
    }
    @Test void overAllocationIsInvalidWithWarning() {
        var r = AllocationCalculator.calculate(new Input(100_000, 0, 0, 0, List.of(fixed("g", 150_000))));
        assertFalse(r.valid()); assertFalse(r.warnings().isEmpty());
    }
    @Test void percentagesOver100AreInvalid() {
        assertFalse(AllocationCalculator.calculate(new Input(100_000, 0, 0, 0, List.of(pct("a", 6000), pct("b", 6000)))).valid());
    }
    @Test void reservesAndCommitmentsReduceAllocatableAndWarnOnThinBuffer() {
        var r = AllocationCalculator.calculate(new Input(1_000_000, 200_000, 300_000, 150_000, List.of(fixed("g", 400_000))));
        assertEquals(500_000, r.allocatableMinor()); assertEquals(100_000, r.remainingMinor());
        assertTrue(r.valid()); assertEquals(1, r.warnings().size());
    }
    @Test void negativeInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AllocationCalculator.calculate(new Input(-1, 0, 0, 0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> AllocationCalculator.calculate(new Input(100, 0, 0, 0, List.of(fixed("g", -5)))));
    }
    @Test void isDeterministic() {
        var in = new Input(1_000_000, 0, 0, 0, List.of(pct("e", 1000)));
        assertEquals(AllocationCalculator.calculate(in), AllocationCalculator.calculate(in));
    }
}
