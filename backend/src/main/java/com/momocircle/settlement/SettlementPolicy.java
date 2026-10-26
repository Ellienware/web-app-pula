package com.momocircle.settlement;

import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;

import java.math.BigDecimal;

/**
 * Domain-specific settlement rules behind one shared engine. Policies decide WHAT counts as gross, WHEN a sale is earned,
 * and which rates apply; the engine owns the lifecycle, payout, idempotency, adjustments and reporting.
 */
public interface SettlementPolicy {

    SettlementSourceType sourceType();

    /** Loads the source and its payment. Throws a not-found ApiException when the source does not exist. */
    SettlementFacts facts(String sourceId);

    Rates rates();

    /** Minutes between "earned" and "payable" (the dispute window). */
    int disputeWindowMinutes();

    /** Refunds already recorded against this sale (zero where no refund path exists for the domain). */
    BigDecimal refunded(SettlementFacts facts);
}
