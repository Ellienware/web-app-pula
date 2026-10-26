package com.momocircle.domain.enums;

/**
 * The restaurant's response to a paid food order. Null on retail orders. WITHDRAWN means the
 * customer cancelled before the restaurant decided. Stored as VARCHAR(16).
 */
public enum RestaurantDecision { PENDING, ACCEPTED, REJECTED, WITHDRAWN }
