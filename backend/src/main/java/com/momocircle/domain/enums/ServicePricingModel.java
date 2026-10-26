package com.momocircle.domain.enums;

/** How a listing is priced. The provider's quote must stay consistent with it (checked on the server). */
public enum ServicePricingModel {
    FIXED, HOURLY, QUOTE_REQUIRED, STARTING_FROM
}
