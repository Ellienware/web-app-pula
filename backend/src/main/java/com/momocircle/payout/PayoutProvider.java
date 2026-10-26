package com.momocircle.payout;

import com.momocircle.domain.enums.PayoutStatus;

import java.math.BigDecimal;

/**
 * Sends money from PULA to a recipient (a driver's delivery earnings, or a restaurant's settlement).
 * Payouts are a separate concept from customer payments and never share the PaymentProvider
 * abstraction. {@code pay} MUST be idempotent on {@code idempotencyKey}: repeating a call with the same
 * key returns the original outcome and never moves money twice.
 */
public interface PayoutProvider {
    String name();
    /** True when no real money moves (mock). Surfaced to clients so it can be labelled. */
    boolean sandbox();
    PayoutResult pay(PayoutRequest request);

    record PayoutRequest(String idempotencyKey, BigDecimal amount, String currency,
                         String recipientMsisdn, String narrative) {}

    record PayoutResult(PayoutStatus status, String providerReference, String failureReason) {}
}
