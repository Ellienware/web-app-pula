package com.momocircle.future;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Estimates how much per period would be needed to close a remaining goal amount by a date.
 * Pure arithmetic. It does not schedule, debit or create anything; it is a suggestion for the user.
 * Periods = whole periods from today up to and including the deadline (at least 1 if the deadline is today or later).
 * Per-period amount is rounded UP to the cent so the plan never under-shoots the target.
 */
public final class ScheduleCalculator {
    public enum Frequency { WEEKLY, MONTHLY }

    public record Result(long remainingMinor, int periods, long perPeriodMinor, List<String> warnings) {}

    private ScheduleCalculator() {}

    public static Result estimate(long targetMinor, long recordedMinor, LocalDate today, LocalDate deadline, Frequency f) {
        if (targetMinor < 0 || recordedMinor < 0) throw new IllegalArgumentException("Amounts must not be negative");
        List<String> w = new ArrayList<>();
        long remaining = Math.max(0, targetMinor - recordedMinor);
        if (remaining == 0) {
            w.add("The recorded progress already meets the target.");
            return new Result(0, 0, 0, w);
        }
        if (deadline == null) {
            w.add("Set a target date to estimate a schedule.");
            return new Result(remaining, 0, 0, w);
        }
        if (deadline.isBefore(today)) {
            w.add("The target date has passed; choose a later date to estimate a schedule.");
            return new Result(remaining, 0, 0, w);
        }
        long periods = f == Frequency.WEEKLY
                ? Math.max(1, ChronoUnit.DAYS.between(today, deadline) / 7)
                : Math.max(1, ChronoUnit.MONTHS.between(today, deadline));
        long perPeriod = (remaining + periods - 1) / periods;
        w.add("This is an estimate only. Recorded progress comes from the existing PULA workflow; nothing is scheduled or transferred.");
        return new Result(remaining, (int) periods, perPeriod, w);
    }
}
