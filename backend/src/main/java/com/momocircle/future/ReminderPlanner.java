package com.momocircle.future;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides WHICH reminders are due. Pure date logic. A reminder is only a notification: it never creates a contribution,
 * transfer or payment. De-duplication is by the returned key (stored in reminder_log with a unique constraint).
 */
public final class ReminderPlanner {

    public static final int[] GOAL_LEAD_DAYS = {7, 1};

    private ReminderPlanner() {}

    /** Keys for goal-date reminders due today. A window is due on or after (deadline - lead) until the deadline passes. */
    public static List<String> goalDeadlineKeys(String goalId, LocalDate deadline, LocalDate today) {
        List<String> keys = new ArrayList<>();
        if (deadline == null || today.isAfter(deadline)) return keys;
        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, deadline);
        // Only the tightest applicable window fires, so a user who opts in 3 days before the date gets one reminder, not two.
        for (int i = GOAL_LEAD_DAYS.length - 1; i >= 0; i--) {
            if (daysLeft <= GOAL_LEAD_DAYS[i]) { keys.add("GOAL_DEADLINE:" + goalId + ":" + deadline + ":" + GOAL_LEAD_DAYS[i]); break; }
        }
        return keys;
    }

    public static String planReviewKey(LocalDate today) { return "PLAN_REVIEW:" + YearMonth.from(today); }

    public static String saveToBuyKey(String goalId, int percentBucket) { return "SAVE_TO_BUY:" + goalId + ":" + percentBucket; }

    /** Progress bucket (50 or 90) once recorded progress has crossed it, else 0. For a one-time progress nudge per bucket. */
    public static int progressBucket(long recordedMinor, long targetMinor) {
        if (targetMinor <= 0 || recordedMinor <= 0 || recordedMinor >= targetMinor) return 0;
        long pct = recordedMinor * 100 / targetMinor;
        return pct >= 90 ? 90 : pct >= 50 ? 50 : 0;
    }
}
