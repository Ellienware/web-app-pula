package com.momocircle.domain.enums;

/**
 * What a PULA Business is allowed to do on the platform. Deliberately
 * independent of {@link BusinessType} (which describes *what kind* of
 * business this is, e.g. spaza vs formal retailer) — capabilities describe
 * *what it can transact*, and a single business can hold more than one at
 * once (brief §6: "A business may have multiple capabilities").
 *
 * Examples from the brief:
 *   Spaza / informal trader   -> CAN_SELL_TO_CONSUMERS, CAN_BUY_STOCK
 *   Formal retailer           -> CAN_SELL_TO_CONSUMERS
 *   Wholesaler / supplier     -> CAN_SUPPLY_BUSINESSES, CAN_MANAGE_DELIVERY_FLEET
 *
 * Stored as a {@code @ElementCollection} on {@link com.momocircle.domain.Business},
 * mirroring the existing {@code fulfilmentOptions} pattern rather than
 * introducing a new persistence style.
 */
public enum BusinessCapability {
    /** Can list products/services and sell to consumers via PULA Market — Shop. */
    CAN_SELL_TO_CONSUMERS,
    /** Can buy stock from suppliers via PULA Market — Stock. */
    CAN_BUY_STOCK,
    /** Can register a wholesale catalogue and receive stock orders from other businesses. */
    CAN_SUPPLY_BUSINESSES,
    /** Can manage a pool of drivers that PULA Ride can dispatch on this business's behalf. */
    CAN_MANAGE_DELIVERY_FLEET,
    /** Can list services, receive service requests, quote and complete bookings via PULA Market - Services. */
    CAN_PROVIDE_SERVICES
}
