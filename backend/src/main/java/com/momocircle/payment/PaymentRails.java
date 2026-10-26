package com.momocircle.payment;

import com.momocircle.domain.Payment;

/**
 * Names the payment rails a {@link Payment} can have been created on. There is exactly one rail that moves value:
 * {@link #WALLET}, the SIMULATED PULA Money wallet backed by the shared ledger. Nothing here can contact a bank, card
 * network or mobile-money operator, and a wallet payment never settles a real-world obligation.
 *
 * <p>Historical payments keep whatever {@code provider} string they were stored with ("sandbox", "paystack...", ...);
 * only new wallet checkouts carry {@link #WALLET}.
 */
public final class PaymentRails {

    /** Persisted in {@code payments.provider} for orders paid from the simulated PULA Money wallet. */
    public static final String WALLET = "wallet";

    /** Mode label shown to clients for a wallet checkout session. Always simulated. */
    public static final String WALLET_MODE = "WALLET_SIMULATED";

    private PaymentRails() { }

    /**
     * The ledger idempotency key of the one debit that pays this payment. Deterministic, so a retry, a double tap or the
     * crash-recovery sweep always reaches the same ledger transaction instead of posting a second one.
     */
    public static String ledgerKey(String paymentId) {
        return "mkt:" + paymentId;
    }

    public static boolean isWallet(Payment p) {
        return p != null && WALLET.equals(p.getProvider());
    }
}
