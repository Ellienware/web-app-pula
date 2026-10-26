package com.momocircle.future;

/**
 * Where an income basis came from. Neither value is verified settlement. Orders, payments, settlements and wallet
 * credits are intentionally NOT summed here: they can represent the same economic event and no verified-settlement
 * source exists, so they are not offered as a basis.
 */
public enum IncomeSource {
    USER_ENTERED,
    BUSINESS_MANUAL_ENTRIES,
    /** Net of the business's merchant settlements marked PAID. The payout is simulated, so this is NOT bank-verified income. */
    MERCHANT_SIMULATED_SETTLEMENTS
}
