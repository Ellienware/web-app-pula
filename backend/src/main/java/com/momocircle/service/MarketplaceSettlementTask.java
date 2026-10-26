package com.momocircle.service;

import com.momocircle.settlement.MarketplaceSettlementService;
import com.momocircle.settlement.SettlementPayoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives payouts for every settlement source through the shared engine. Each settlement is its own transaction, so one
 * failure never blocks the rest. Safe to run alongside FoodOrderMaintenanceTask's payout pass: a settlement is claimed
 * atomically, so only one runner ever sends it, and the provider call carries a stable idempotency key.
 */
@Component
public class MarketplaceSettlementTask {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSettlementTask.class);
    private final MarketplaceSettlementService settlements;

    public MarketplaceSettlementTask(MarketplaceSettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(fixedDelayString = "${app.marketplace.settlement.payout-run-ms:60000}", initialDelayString = "${app.marketplace.settlement.initial-delay-ms:90000}")
    public void run() {
        try {
            for (SettlementPayoutService.DuePayout due : settlements.dueForPayout()) {
                try {
                    settlements.pay(due.settlementId(), due.stuck());
                } catch (RuntimeException e) {
                    log.warn("Settlement payout failed for {}", due.settlementId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Settlement payout pass failed", e);
        }
    }
}
