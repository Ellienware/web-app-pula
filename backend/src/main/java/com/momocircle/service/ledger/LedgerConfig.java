package com.momocircle.service.ledger;

import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Wires the framework-free {@link LedgerEngine} to the database-backed store. Limits are PROVISIONAL demo values in
 * minor units (R10,000 = 1,000,000), overridable per environment, e.g.
 * {@code app.ledger.limits.transfer-max-minor} / env {@code APP_LEDGER_LIMITS_TRANSFER_MAX_MINOR}.
 */
@Configuration
public class LedgerConfig {

    @Bean
    public LedgerLimits ledgerLimits(
            @Value("${app.ledger.limits.transfer-max-minor:1000000}") long transferMaxMinor,
            @Value("${app.ledger.limits.issuance-per-request-max-minor:500000}") long issuancePerRequestMaxMinor,
            @Value("${app.ledger.limits.issuance-per-user-window-max-minor:2000000}") long issuancePerUserWindowMaxMinor,
            @Value("${app.ledger.limits.issuance-window-days:30}") long issuanceWindowDays,
            @Value("${app.ledger.limits.withdrawal-max-minor:1000000}") long withdrawalMaxMinor) {
        return new LedgerLimits(transferMaxMinor, issuancePerRequestMaxMinor, issuancePerUserWindowMaxMinor,
                withdrawalMaxMinor, Duration.ofDays(issuanceWindowDays));
    }

    @Bean
    public LedgerEngine ledgerEngine(JpaLedgerStore store, LedgerLimits limits) {
        return new LedgerEngine(store, limits, () -> UUID.randomUUID().toString(), Clock.systemUTC());
    }
}
