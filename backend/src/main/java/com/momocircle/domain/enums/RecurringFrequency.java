package com.momocircle.domain.enums;

import java.time.LocalDate;

/** Cadence of a recurring contribution plan. */
public enum RecurringFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY;

    /** Returns the next due date after {@code from} for this cadence. */
    public LocalDate advance(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case YEARLY -> from.plusYears(1);
        };
    }
}
