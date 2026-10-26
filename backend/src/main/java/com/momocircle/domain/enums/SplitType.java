package com.momocircle.domain.enums;

public enum SplitType {
    /** Split evenly across all participants (remainder assigned deterministically). */
    EQUAL,
    /** Caller supplies an explicit amount per participant. */
    CUSTOM,
    /** Caller supplies a percentage per participant (two decimals, total exactly 100.00). Largest remainder rounds to minor units. */
    PERCENTAGE,
    /** Caller supplies line items and who shares each item; each item is split equally among its assignees. */
    ITEM_BASED
}
