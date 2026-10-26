package com.momocircle.branch;

/**
 * Explicit, per-assignment permissions for a branch. The business owner always has all of them. A MANAGER or STAFF member has exactly
 * the set stored on their assignment (a new MANAGER with none specified gets {@link #MANAGER_DEFAULTS}, a new STAFF member gets none).
 */
public enum BranchPermission {
    /** Move stock between branches (needs this at BOTH branches) and see the stock movement history. */
    MANAGE_STOCK,
    /** Set which products a branch sells and its branch price. */
    MANAGE_PRICES,
    /** See the branch's orders. */
    VIEW_ORDERS,
    /** Move the branch's orders through fulfilment (processing, ready, completed). */
    FULFIL_ORDERS,
    /** See the branch's sales report. */
    VIEW_REPORTS;

    public static final java.util.Set<BranchPermission> MANAGER_DEFAULTS = java.util.Collections.unmodifiableSet(
            java.util.EnumSet.allOf(BranchPermission.class));
}
