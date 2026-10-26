package com.momocircle.settlement;

import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.SettlementType;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.service.NotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Predicate;

@Configuration
public class MarketplaceSettlementConfig {

    /**
     * The shared payout engine for all sources. "Earned" (used when a hold is released) is asked of the owning domain's
     * policy, so retail, stock and services each decide for themselves.
     */
    @Bean
    public SettlementPayoutService settlementPayoutService(RestaurantSettlementRepository settlements, PayoutOrchestrator orchestrator,
                                                           NotificationService notifications, MarketplaceSettlementProperties props,
                                                           List<SettlementPolicy> policies) {
        Predicate<RestaurantSettlement> earned = s -> {
            if (s.getType() == SettlementType.MERCHANT_PROTECTION) return true;
            return policies.stream().filter(p -> p.sourceType() == s.getSourceType()).findFirst()
                    .map(p -> p.facts(s.getSourceId()).earned()).orElse(false);
        };
        return new SettlementPayoutService(settlements, orchestrator, notifications, props.getMaxPayoutAttempts(),
                props.getStuckProcessingMinutes(), earned);
    }
}
