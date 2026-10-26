package com.momocircle.domain.enums;

/** What happens when the target quantity is not reached by the deadline. Configured per group; never silent. */
public enum BulkShortfallPolicy {
    /** Cancel the group. Nothing has been charged. */
    CANCEL,
    /** Extend the deadline by the configured hours (bounded number of times), then cancel. */
    EXTEND,
    /** If the minimum order quantity is met, move to CONFIRMATION so each business re-approves the (possibly higher) price. */
    ASK_PARTICIPANTS
}
