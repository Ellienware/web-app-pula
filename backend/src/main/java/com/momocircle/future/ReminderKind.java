package com.momocircle.future;

/**
 * Opt-in reminder types. Stokvel contribution reminders are NOT here: the existing stokvel notice workflow already raises
 * STOKVEL_CONTRIBUTION_DUE / MISSED notifications, and duplicating them would double-notify.
 */
public enum ReminderKind {
    GOAL_DEADLINE("Remind me 7 days and 1 day before a goal's target date"),
    PLAN_REVIEW("Remind me once a month to review my allocation plans"),
    SAVE_TO_BUY_PROGRESS("Tell me when a Save-to-Buy goal passes 50% and 90% of its target");

    private final String description;
    ReminderKind(String d) { this.description = d; }
    public String description() { return description; }
}
