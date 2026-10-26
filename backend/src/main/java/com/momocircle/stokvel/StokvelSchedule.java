package com.momocircle.stokvel;

import com.momocircle.domain.enums.RecurringFrequency;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Deterministic contribution periods. Period k starts at start + k*frequency (anchored to the start date, so a
 * monthly plan starting on the 31st does not drift) and ends the day before period k+1 starts.
 */
public final class StokvelSchedule {

    private StokvelSchedule() {}

    public static LocalDate periodStart(LocalDate start, RecurringFrequency f, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("period index must be >= 0");
        }
        return switch (f) {
            case WEEKLY -> start.plusWeeks(index);
            case BIWEEKLY -> start.plusWeeks(2L * index);
            case MONTHLY -> start.plusMonths(index);
            case QUARTERLY -> start.plusMonths(3L * index);
            case YEARLY -> start.plusYears(index);
        };
    }

    /** Exclusive end = the start of the next period. */
    public static LocalDate periodEndExclusive(LocalDate start, RecurringFrequency f, int index) {
        return periodStart(start, f, index + 1);
    }

    /** Index of the period containing {@code on}, or -1 when {@code on} is before the start date. */
    public static int periodIndex(LocalDate start, RecurringFrequency f, LocalDate on) {
        if (on.isBefore(start)) {
            return -1;
        }
        long days = ChronoUnit.DAYS.between(start, on);
        int est = switch (f) {
            case WEEKLY -> (int) (days / 7);
            case BIWEEKLY -> (int) (days / 14);
            case MONTHLY -> (int) ChronoUnit.MONTHS.between(start, on);
            case QUARTERLY -> (int) (ChronoUnit.MONTHS.between(start, on) / 3);
            case YEARLY -> (int) ChronoUnit.YEARS.between(start, on);
        };
        while (!periodStart(start, f, est + 1).isAfter(on)) {
            est++;
        }
        while (est > 0 && periodStart(start, f, est).isAfter(on)) {
            est--;
        }
        return est;
    }

    /** A period is over once {@code today} has reached the start of the next period. */
    public static boolean hasEnded(LocalDate start, RecurringFrequency f, int index, LocalDate today) {
        return !today.isBefore(periodEndExclusive(start, f, index));
    }
}
