package com.momocircle.domain.enums;

/**
 * How a stock order can be fulfilled between a buying business and its supplier
 * (brief §32). Kept separate from {@link FulfilmentOption} (which governs
 * consumer orders) to avoid conflating the two order types and to allow
 * B2B-specific options (e.g. WHOLESALER_DELIVERY) without polluting the
 * consumer fulfilment model.
 */
public enum StockFulfilmentOption {
    /** Buyer collects stock directly from the supplier's premises. Delivery fee = R0. */
    SELF_COLLECTION,
    /** Supplier delivers using their own vehicle/driver pool. */
    WHOLESALER_DELIVERY,
    /** PULA Ride dispatches an independent driver to collect and deliver. */
    PULA_RIDE_DELIVERY
}
