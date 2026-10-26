package com.momocircle.future;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class ReminderPlannerTest {
    private static final LocalDate D = LocalDate.of(2026, 11, 30);
    @Test void farOutPastAndMissingDatesGiveNothing() {
        assertTrue(ReminderPlanner.goalDeadlineKeys("g", D, LocalDate.of(2026, 10, 1)).isEmpty());
        assertTrue(ReminderPlanner.goalDeadlineKeys("g", D, D.plusDays(1)).isEmpty());
        assertTrue(ReminderPlanner.goalDeadlineKeys("g", null, D).isEmpty());
    }
    @Test void sevenDayAndOneDayWindowsFireOncePerWindow() {
        assertEquals("GOAL_DEADLINE:g:2026-11-30:7", ReminderPlanner.goalDeadlineKeys("g", D, LocalDate.of(2026, 11, 23)).get(0));
        assertEquals("GOAL_DEADLINE:g:2026-11-30:7", ReminderPlanner.goalDeadlineKeys("g", D, LocalDate.of(2026, 11, 25)).get(0));
        assertEquals("GOAL_DEADLINE:g:2026-11-30:1", ReminderPlanner.goalDeadlineKeys("g", D, LocalDate.of(2026, 11, 29)).get(0));
        assertEquals(1, ReminderPlanner.goalDeadlineKeys("g", D, D).size());
    }
    @Test void monthlyReviewAndProgressBuckets() {
        assertEquals("PLAN_REVIEW:2026-10", ReminderPlanner.planReviewKey(LocalDate.of(2026, 10, 23)));
        assertEquals(0, ReminderPlanner.progressBucket(10, 100)); assertEquals(50, ReminderPlanner.progressBucket(55, 100));
        assertEquals(90, ReminderPlanner.progressBucket(95, 100)); assertEquals(0, ReminderPlanner.progressBucket(100, 100));
    }
}
