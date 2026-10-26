package com.momocircle.risk;

import java.math.BigDecimal;

/**
 * What a payment path tells the risk layer about the transaction it is about to start. Everything here is derived by the
 * BACKEND after ordinary validation; nothing comes from the client except through the already-validated order/booking.
 *
 * @param referenceKey   stable id of the thing being paid (order id, booking id, idempotency key...)
 * @param recipientKey   stable id of the recipient (business id, taxi id, circle id) or null
 * @param ownershipValid defence in depth: the caller has already verified ownership, this must be true
 * @param recipientActive false if the recipient business is deactivated; null when there is no recipient business
 * @param simulated      true when the payment context is sandbox/simulated funds
 */
public record GateRequest(RiskType type, String referenceKey, BigDecimal amount, String currency, String recipientKey,
                          String businessId, boolean ownershipValid, Boolean recipientActive, boolean simulated) {}
