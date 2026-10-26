package com.momocircle.domain.enums;

/**
 * How a Business can get an order to a customer. A business can support more
 * than one — stored as a set on Business, and (once orders carry a chosen
 * fulfilment method) each Order picks exactly one from its seller's set.
 */
public enum FulfilmentOption {
    /** Customer collects the order themselves. */
    CUSTOMER_PICKUP,
    /** The seller delivers it themselves. */
    SELLER_DELIVERY,
    /** Delivered by a MoMoRide driver — see the momoride package for the actual booking flow. */
    MOMORIDE_DELIVERY,
    /** Delivered by some other supported logistics partner. */
    SUPPORTED_PARTNER_DELIVERY
}
