package com.momocircle.domain.enums;

/** Purpose of a stokvel (a Circle of type STOKVEL). Stored on the stokvel profile, never on ordinary circles. */
public enum StokvelType {
    /** Members take turns receiving the pot. The only type with a payout rotation. */
    ROTATIONAL,
    GOAL_BASED,
    GROCERY,
    STOCK,
    BUSINESS_EQUIPMENT,
    EMERGENCY,
    COMMUNITY_PROJECT,
    GENERAL
}
