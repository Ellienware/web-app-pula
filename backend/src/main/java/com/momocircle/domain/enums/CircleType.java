package com.momocircle.domain.enums;

/** The kind of savings/finance circle. */
public enum CircleType {
    /** Everyone contributes toward a shared goal (e.g. a group gift, event). */
    SAVINGS_GOAL,
    /** Rotating savings & credit ("susu"/"tontine"): members take turns receiving the pot. */
    ROTATING,
    /** Recurring shared expense pool (e.g. rent, subscriptions). */
    EXPENSE_POOL,
    /** Community stokvel: a circle with a stokvel profile (see StokvelType). Created only via /api/stokvels. */
    STOKVEL
}
