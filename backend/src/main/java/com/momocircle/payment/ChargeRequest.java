package com.momocircle.payment;

import java.math.BigDecimal;

/**
 * A request to collect money from a payer's mobile-money wallet.
 *
 * @param amount         amount to collect
 * @param currency       ISO 4217 currency
 * @param payerMsisdn    payer's mobile-money number
 * @param idempotencyKey unique key so retries never double-charge
 * @param description    human-readable narrative shown to the payer
 */
public record ChargeRequest(
        BigDecimal amount,
        String currency,
        String payerMsisdn,
        String idempotencyKey,
        String description
) {}
