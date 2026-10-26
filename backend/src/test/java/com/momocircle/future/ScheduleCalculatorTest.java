package com.momocircle.future;

import com.momocircle.future.ScheduleCalculator.Frequency;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleCalculatorTest {
    private static final LocalDate T = LocalDate.of(2026, 10, 23);
    @Test void monthlyEvenSplit() {
        var r = ScheduleCalculator.estimate(1_200_000, 0, T, LocalDate.of(2027, 10, 23), Frequency.MONTHLY);
        assertEquals(12, r.periods()); assertEquals(100_000, r.perPeriodMinor());
    }
    @Test void roundsUpSoTargetIsNotMissed() {
        assertEquals(333_334, ScheduleCalculator.estimate(1_000_000, 0, T, LocalDate.of(2027, 1, 23), Frequency.MONTHLY).perPeriodMinor());
    }
    @Test void weeklyAfterExistingProgress() {
        assertEquals(200_000, ScheduleCalculator.estimate(1_000_000, 400_000, T, LocalDate.of(2026, 11, 13), Frequency.WEEKLY).perPeriodMinor());
    }
    @Test void metTargetNoDeadlineAndPastDeadlineGiveNoSchedule() {
        assertEquals(0, ScheduleCalculator.estimate(100, 200, T, null, Frequency.MONTHLY).perPeriodMinor());
        assertEquals(0, ScheduleCalculator.estimate(100, 0, T, null, Frequency.MONTHLY).periods());
        assertEquals(0, ScheduleCalculator.estimate(100, 0, T, T.minusDays(1), Frequency.MONTHLY).perPeriodMinor());
    }
}
