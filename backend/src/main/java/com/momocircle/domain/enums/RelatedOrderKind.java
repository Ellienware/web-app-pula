package com.momocircle.domain.enums;

/** Which marketplace order table a stokvel request points at. */
public enum RelatedOrderKind {
    /** Retail/food order (orders table). */
    ORDER,
    /** Wholesale stock order (stock_orders table). */
    STOCK_ORDER,
    /** A paid-for service booking (PULA Market - Services). */
    SERVICE_BOOKING,
    /** Non-marketplace spend recorded with a free-text reference. */
    EXTERNAL
}
