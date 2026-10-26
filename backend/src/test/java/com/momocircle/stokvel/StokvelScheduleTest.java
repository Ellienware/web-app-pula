package com.momocircle.stokvel;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.RecurringFrequency;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StokvelScheduleTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 31);

    @Test
    void monthlyPeriodsAreAnchoredToTheStartDateAndDoNotDrift() {
        assertEquals(LocalDate.of(2026, 2, 28), StokvelSchedule.periodStart(START, RecurringFrequency.MONTHLY, 1));
        assertEquals(LocalDate.of(2026, 3, 31), StokvelSchedule.periodStart(START, RecurringFrequency.MONTHLY, 2));
    }

    @Test
    void periodIndexBeforeStartIsMinusOne() {
        assertEquals(-1, StokvelSchedule.periodIndex(START, RecurringFrequency.MONTHLY, LocalDate.of(2026, 1, 30)));
    }

    @Test
    void periodIndexFindsTheContainingPeriod() {
        assertEquals(0, StokvelSchedule.periodIndex(START, RecurringFrequency.MONTHLY, START));
        assertEquals(0, StokvelSchedule.periodIndex(START, RecurringFrequency.MONTHLY, LocalDate.of(2026, 2, 27)));
        assertEquals(1, StokvelSchedule.periodIndex(START, RecurringFrequency.MONTHLY, LocalDate.of(2026, 2, 28)));
        assertEquals(2, StokvelSchedule.periodIndex(START, RecurringFrequency.MONTHLY, LocalDate.of(2026, 3, 31)));
    }

    @Test
    void weeklyAndBiweeklyAndQuarterly() {
        LocalDate s = LocalDate.of(2026, 1, 1);
        assertEquals(2, StokvelSchedule.periodIndex(s, RecurringFrequency.WEEKLY, LocalDate.of(2026, 1, 15)));
        assertEquals(1, StokvelSchedule.periodIndex(s, RecurringFrequency.BIWEEKLY, LocalDate.of(2026, 1, 15)));
        assertEquals(1, StokvelSchedule.periodIndex(s, RecurringFrequency.QUARTERLY, LocalDate.of(2026, 4, 1)));
        assertEquals(0, StokvelSchedule.periodIndex(s, RecurringFrequency.QUARTERLY, LocalDate.of(2026, 3, 31)));
    }

    @Test
    void periodEndsOnTheDayTheNextPeriodStarts() {
        LocalDate s = LocalDate.of(2026, 1, 1);
        assertFalse(StokvelSchedule.hasEnded(s, RecurringFrequency.MONTHLY, 0, LocalDate.of(2026, 1, 31)));
        assertTrue(StokvelSchedule.hasEnded(s, RecurringFrequency.MONTHLY, 0, LocalDate.of(2026, 2, 1)));
    }

    @Test
    void negativeIndexIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StokvelSchedule.periodStart(START, RecurringFrequency.MONTHLY, -1));
    }
}
