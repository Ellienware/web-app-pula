package com.momocircle.service;

import java.math.BigDecimal;

/**
 * Thin seam over the EXISTING market payment path (PaymentOrchestrator / Paystack). It is not a payment provider:
 * the production implementation just forwards to PaymentOrchestrator, the same call stock and retail checkout use.
 */
public interface ServicePaymentGateway {

    record Session(String reference, String checkoutUrl) {}

    Session start(String reference, BigDecimal amount, String currency, String payerEmail);

    /** Provider label stored on the Payment row, same as stock/retail. */
    String providerLabel();
}
