package com.momocircle.branch;

/**
 * Operational status of a branch. ACTIVE branches can take orders (subject to the existing "temporarily closed" flag);
 * SUSPENDED is reversible; CLOSED is permanent (history is kept, the branch is never deleted and cannot be reopened here).
 * The legacy boolean {@code business_branches.active} mirrors {@code status == ACTIVE} so existing PULA Food code keeps working.
 */
public enum BranchStatus {
    ACTIVE, SUSPENDED, CLOSED;

    public boolean acceptsOrders() { return this == ACTIVE; }

    public static boolean canTransition(BranchStatus from, BranchStatus to) {
        if (from == null || to == null || from == to) return false;
        return switch (from) {
            case ACTIVE -> to == SUSPENDED || to == CLOSED;
            case SUSPENDED -> to == ACTIVE || to == CLOSED;
            case CLOSED -> false;
        };
    }
}
