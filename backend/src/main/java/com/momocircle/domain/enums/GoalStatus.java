package com.momocircle.domain.enums;

public enum GoalStatus {
    /** Actively accepting contributions. */
    ACTIVE,
    /** Target amount has been reached. */
    REACHED,
    /** Temporarily not accepting new contributions. Existing records are untouched; an admin can resume it. */
    PAUSED,
    /** Manually closed by an admin (target may or may not have been met). */
    CLOSED
}
