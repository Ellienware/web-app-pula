package com.momocircle.sms;

/**
 * Abstraction over an SMS gateway, so MoMoRide's basic-phone channel (payment
 * confirmations, ride booking/cancellation notices) can be wired to a real
 * telecom SMS provider later without touching any call site. Mirrors the
 * shape of {@link com.momocircle.payment.PaymentProvider} deliberately.
 */
public interface SmsProvider {

    /** Stable identifier for the active implementation, e.g. "sandbox". */
    String name();

    /**
     * Send a plain-text SMS. Implementations must never throw for an
     * ordinary delivery failure — SMS is a best-effort notification channel,
     * not part of any financial transaction's success/failure path.
     */
    void send(String toMsisdn, String message);
}
