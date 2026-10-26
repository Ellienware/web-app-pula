package com.momocircle.payment;

/**
 * Legacy abstraction for the SIMULATED payment path. The only implementation is
 * {@link SandboxPaymentProvider}; no real provider (MTN MoMo was retired in Phase 10) exists, and a
 * SUCCESSFUL status from it never means real money moved. Slated for replacement by explicit,
 * capability-checked operation types (see docs/PHASE_10_AUDIT.md, Step 2).
 */
public interface PaymentProvider {

    /** Stable identifier persisted on each Payment, always "sandbox" for new rows (historical rows may hold other values). */
    String name();

    /** Collect money from a payer's wallet. */
    ChargeResult charge(ChargeRequest request);

    /** Reverse a previously successful charge. */
    ChargeResult refund(String providerReference, String idempotencyKey);

    /**
     * Polls the current state of a previously-initiated charge. Used to resolve
     * payments that came back PROCESSING (never a client-side "it worked"
     * message) — mirrors MTN MoMo's documented
     * GET /collection/v1_0/requesttopay/{referenceId} status check.
     */
    ChargeResult getStatus(String providerReference);
}
