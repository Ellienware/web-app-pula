package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;

/**
 * The outcome of a charge/refund attempt as reported by a provider.
 *
 * @param status            resulting payment status
 * @param providerReference provider-side transaction id
 * @param failureReason     populated when status is FAILED
 */
public record ChargeResult(
        PaymentStatus status,
        String providerReference,
        String failureReason
) {
    public static ChargeResult of(PaymentStatus status, String providerReference) {
        return new ChargeResult(status, providerReference, null);
    }

    public static ChargeResult failed(String reason) {
        return new ChargeResult(PaymentStatus.FAILED, null, reason);
    }
}
