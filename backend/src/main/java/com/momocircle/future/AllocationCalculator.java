package com.momocircle.future;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Smart Allocation calculation. PLANNING ONLY: pure arithmetic over integer minor units (cents).
 * It never touches a wallet, ledger, payment provider or contribution. Nothing here moves money.
 *
 * Rounding: percentage lines use basis points (1% = 100 bp) and round HALF_UP to the cent per line, applied in
 * the caller's line order. Fixed lines are exact. Fixed lines are allocated first, then percentage lines on the
 * income basis (not on the remainder), so results do not depend on line order except for the cap rule below.
 */
public final class AllocationCalculator {

    public enum LineType { FIXED, PERCENT }

    public record Line(String goalId, String label, LineType type, long fixedMinor, int percentBasisPoints) {}

    public record Input(long incomeBasisMinor, long reservesMinor, long commitmentsMinor, long minOperatingBufferMinor,
                        List<Line> lines) {}

    public record LineResult(String goalId, String label, long allocatedMinor) {}

    public record Result(boolean valid, long incomeBasisMinor, long reservesMinor, long commitmentsMinor,
                         long allocatableMinor, long totalAllocatedMinor, long remainingMinor,
                         List<LineResult> lines, List<String> warnings) {}

    private AllocationCalculator() {}

    public static Result calculate(Input in) {
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        if (in.incomeBasisMinor() < 0 || in.reservesMinor() < 0 || in.commitmentsMinor() < 0 || in.minOperatingBufferMinor() < 0) {
            throw new IllegalArgumentException("Amounts must not be negative");
        }
        long allocatable = Math.max(0, in.incomeBasisMinor() - in.reservesMinor() - in.commitmentsMinor());
        if (in.reservesMinor() + in.commitmentsMinor() > in.incomeBasisMinor()) {
            warnings.add("Declared reserves and commitments exceed the income basis; nothing is available to allocate.");
        }
        long pctTotalBp = 0;
        List<LineResult> out = new ArrayList<>();
        long total = 0;
        for (Line l : in.lines()) {
            long amt;
            if (l.type() == LineType.FIXED) {
                if (l.fixedMinor() < 0) throw new IllegalArgumentException("Fixed amount must not be negative");
                amt = l.fixedMinor();
            } else {
                if (l.percentBasisPoints() < 0 || l.percentBasisPoints() > 10_000)
                    throw new IllegalArgumentException("Percentage must be between 0 and 100");
                pctTotalBp += l.percentBasisPoints();
                // half-up: (income * bp + 5000) / 10000, using Math.multiplyExact to fail loudly on overflow
                amt = (Math.multiplyExact(in.incomeBasisMinor(), (long) l.percentBasisPoints()) + 5_000L) / 10_000L;
            }
            total = Math.addExact(total, amt);
            out.add(new LineResult(l.goalId(), l.label(), amt));
        }
        if (pctTotalBp > 10_000) {
            warnings.add("Percentages add up to more than 100%.");
            valid = false;
        }
        if (total > allocatable) {
            warnings.add("Proposed allocations exceed the amount available after declared reserves and commitments. "
                    + "This scenario is invalid and cannot be saved as an active plan.");
            valid = false;
        }
        long remaining = allocatable - total;
        if (valid && in.minOperatingBufferMinor() > 0 && remaining < in.minOperatingBufferMinor()) {
            warnings.add("This plan leaves less than your planned operating buffer. This is a planning figure only; "
                    + "PULA cannot prevent or verify how real money is spent.");
        }
        return new Result(valid, in.incomeBasisMinor(), in.reservesMinor(), in.commitmentsMinor(), allocatable,
                total, remaining, List.copyOf(out), List.copyOf(warnings));
    }
}
