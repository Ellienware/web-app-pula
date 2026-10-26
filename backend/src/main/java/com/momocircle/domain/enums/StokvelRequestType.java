package com.momocircle.domain.enums;

public enum StokvelRequestType {
    WITHDRAWAL,
    MARKETPLACE_PURCHASE,
    STOCK_PURCHASE,
    EMERGENCY_EXPENSE,
    COMMUNITY_EXPENSE;

    /** Purchases are fulfilled through normal PULA checkout by the named buyer. */
    public boolean isMarketplacePurchase() {
        return this == MARKETPLACE_PURCHASE || this == STOCK_PURCHASE;
    }
}
