package com.momocircle.payout;

import org.springframework.stereotype.Component;

/**
 * Routes payout purposes to a provider. DRIVER_EARNINGS (delivery earnings) and RESTAURANT_SETTLEMENT
 * (restaurant sales settlement and merchant-protection compensation) share the one PayoutProvider
 * abstraction but remain separate purposes with separate records (driver_payouts vs
 * restaurant_settlements). SUPPLIER_SETTLEMENT is intentionally NOT added: nothing needs it yet.
 */
@Component
public class PayoutOrchestrator {

    public enum Purpose { DRIVER_EARNINGS, RESTAURANT_SETTLEMENT }

    private final PayoutProvider provider;

    public PayoutOrchestrator(PayoutProvider provider) {
        this.provider = provider;
    }

    public PayoutProvider providerFor(Purpose purpose) {
        return switch (purpose) {
            case DRIVER_EARNINGS, RESTAURANT_SETTLEMENT -> provider;
        };
    }
}
