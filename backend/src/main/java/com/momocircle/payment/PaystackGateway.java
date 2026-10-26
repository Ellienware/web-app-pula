package com.momocircle.payment;

import java.math.BigDecimal;

/**
 * Hosted-checkout collection gateway used for PULA Market payments. Unlike the
 * legacy {@link PaymentProvider} (synchronous mobile-money charge), Paystack is
 * redirect-then-confirm: the backend creates a checkout, the customer pays on
 * Paystack, and PULA only treats the payment as successful after verifying it
 * server-side with Paystack (webhook and/or verify call) — never on a client claim.
 */
public interface PaystackGateway {

    /** "PAYSTACK" (real API, test or live keys) or "SANDBOX_MOCK" (no external call, no real money). */
    String mode();

    CheckoutSession initialize(InitRequest request);

    VerifyResult verify(String reference);

    /**
     * Initiates a refund (full or partial) with the provider. Returns what the provider reported, which
     * for Paystack is normally PENDING/PROCESSING: acceptance is NOT completion. Throws
     * {@link RefundTransportException} when the outcome is unknown (timeout, 5xx) so callers re-check
     * before ever creating a second refund.
     */
    RefundResult createRefund(RefundRequest request);

    /** Current provider state of one refund, by the provider's refund id. */
    RefundResult fetchRefund(String providerRefundId);

    /** All refunds the provider holds for a transaction reference; used to recover from an unknown outcome. */
    java.util.List<RefundResult> listRefunds(String transactionReference);

    record RefundRequest(String transactionReference, long amountMinor, String currency,
                         String customerNote, String merchantNote) {}

    enum RefundRemoteStatus { PENDING, PROCESSING, PROCESSED, FAILED, NEEDS_ATTENTION, NOT_FOUND }

    record RefundResult(RefundRemoteStatus status, String providerRefundId, String transactionReference,
                        long amountMinor, String currency, String rawStatus) {}

    /** The provider could not be reached or answered unusably: the refund may or may not exist remotely. */
    class RefundTransportException extends RuntimeException {
        public RefundTransportException(String message) { super(message); }
    }

    /** The provider answered and refused the refund (e.g. already fully refunded, amount too large). */
    class RefundRejectedException extends RuntimeException {
        public RefundRejectedException(String message) { super(message); }
    }

    record InitRequest(String reference, BigDecimal amount, String currency, String email, String callbackUrl) {}

    /** authorizationUrl is null in SANDBOX_MOCK mode. */
    record CheckoutSession(String reference, String authorizationUrl, String mode) {}

    enum RemoteStatus { SUCCESS, FAILED, PENDING, NOT_FOUND }

    record VerifyResult(RemoteStatus status, long amountMinor, String currency) {}

    static long toMinor(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
