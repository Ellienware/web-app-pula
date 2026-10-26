package com.momocircle.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Configurable, versioned policy. Thresholds are per transaction type. The numbers shipped as defaults are PLACEHOLDERS
 * chosen to be conservative, not calibrated against real PULA loss data; operators must set real values.
 *
 * Score bands (documented): score is an integer 0..100.
 *   score <  stepUpScore            -> ALLOW
 *   stepUpScore <= score < reviewScore -> STEP_UP
 *   score >= reviewScore            -> REVIEW
 * A score alone NEVER produces BLOCK unless {@code scoreCanBlock} is true AND score >= blockScore. BLOCK normally comes from
 * explicit hard rules (ownership, inactive recipient/account, hard amount limit, insufficient funds).
 */
public final class RiskPolicy {

    public record TypePolicy(
            BigDecimal hardLimit,      // above this: BLOCK (hard rule)
            BigDecimal reviewAmount,   // above this: strong amount signal
            BigDecimal stepUpAmount,   // above this: moderate amount signal
            int maxAttemptsPerHour,
            int failureLockThreshold,  // failures in 24h at/after which the transaction is held for REVIEW (hard rule)
            int stepUpScore,
            int reviewScore,
            int blockScore,
            boolean scoreCanBlock) {}

    public enum Mode {
        /** Decisions are enforced. The production default. */
        ENFORCE,
        /** Assess and audit but never hold or block. For a controlled rollout only; set by operators, never by a request. */
        SHADOW
    }

    private final String version;
    private final Mode mode;
    private final Duration validity;
    private final Map<RiskType, TypePolicy> types;

    public RiskPolicy(String version, Mode mode, Duration validity, Map<RiskType, TypePolicy> overrides) {
        this.version = version;
        this.mode = mode;
        this.validity = validity;
        EnumMap<RiskType, TypePolicy> m = new EnumMap<>(RiskType.class);
        for (RiskType t : RiskType.values()) m.put(t, defaultFor(t));
        if (overrides != null) m.putAll(overrides);
        this.types = m;
    }

    public static RiskPolicy defaults() {
        return new RiskPolicy("2026.10-v1", Mode.ENFORCE, Duration.ofMinutes(15), Map.of());
    }

    /** Placeholder defaults (ZAR-scale). Wholesale is larger; taxi fares are small. */
    public static TypePolicy defaultFor(RiskType t) {
        return switch (t) {
            case STOCK_ORDER -> tp("500000", "100000", "30000", 6, 3);
            case CONSUMER_CHECKOUT, LEGACY_ORDER -> tp("50000", "10000", "3000", 8, 3);
            case SERVICE_BOOKING -> tp("50000", "10000", "3000", 8, 3);
            case CONTRIBUTION -> tp("100000", "20000", "5000", 10, 4);
            case TAXI -> tp("2000", "800", "400", 20, 5);
        };
    }

    private static TypePolicy tp(String hard, String review, String stepUp, int attempts, int failures) {
        return new TypePolicy(new BigDecimal(hard), new BigDecimal(review), new BigDecimal(stepUp),
                attempts, failures, 40, 70, 90, false);
    }

    public String version() { return version; }
    public Mode mode() { return mode; }
    public Duration validity() { return validity; }
    public TypePolicy forType(RiskType t) { return types.get(t); }
}
