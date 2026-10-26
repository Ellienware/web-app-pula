package com.momocircle.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator configuration (application.yml / environment only; there is no request, header or role that can change it).
 *
 *   app.risk.mode: ENFORCE (default) | SHADOW (assess and audit, never hold or block: controlled rollout only)
 *   app.risk.rule-set-version, app.risk.validity-minutes
 *   app.risk.taxi-enabled: false (default) - the ONLY switch that can turn a path off, and only for PULA Ride
 *   app.risk.types.<TYPE>.hard-limit / review-amount / step-up-amount / max-attempts-per-hour / failure-lock-threshold /
 *       step-up-score / review-score / block-score / score-can-block
 */
@Component
@ConfigurationProperties(prefix = "app.risk")
public class RiskProperties {

    public static class TypeOverride {
        private BigDecimal hardLimit, reviewAmount, stepUpAmount;
        private Integer maxAttemptsPerHour, failureLockThreshold, stepUpScore, reviewScore, blockScore;
        private Boolean scoreCanBlock;
        public BigDecimal getHardLimit() { return hardLimit; } public void setHardLimit(BigDecimal v) { hardLimit = v; }
        public BigDecimal getReviewAmount() { return reviewAmount; } public void setReviewAmount(BigDecimal v) { reviewAmount = v; }
        public BigDecimal getStepUpAmount() { return stepUpAmount; } public void setStepUpAmount(BigDecimal v) { stepUpAmount = v; }
        public Integer getMaxAttemptsPerHour() { return maxAttemptsPerHour; } public void setMaxAttemptsPerHour(Integer v) { maxAttemptsPerHour = v; }
        public Integer getFailureLockThreshold() { return failureLockThreshold; } public void setFailureLockThreshold(Integer v) { failureLockThreshold = v; }
        public Integer getStepUpScore() { return stepUpScore; } public void setStepUpScore(Integer v) { stepUpScore = v; }
        public Integer getReviewScore() { return reviewScore; } public void setReviewScore(Integer v) { reviewScore = v; }
        public Integer getBlockScore() { return blockScore; } public void setBlockScore(Integer v) { blockScore = v; }
        public Boolean getScoreCanBlock() { return scoreCanBlock; } public void setScoreCanBlock(Boolean v) { scoreCanBlock = v; }
    }

    private String mode = "ENFORCE";
    private String ruleSetVersion = "2026.10-v1";
    private int validityMinutes = 15;
    private boolean taxiEnabled = false;
    private Map<String, TypeOverride> types = new HashMap<>();

    public RiskPolicy toPolicy() {
        RiskPolicy.Mode m = "SHADOW".equalsIgnoreCase(mode) ? RiskPolicy.Mode.SHADOW : RiskPolicy.Mode.ENFORCE; // anything unrecognised enforces
        Map<RiskType, RiskPolicy.TypePolicy> merged = new EnumMap<>(RiskType.class);
        types.forEach((k, o) -> {
            RiskType t;
            try { t = RiskType.valueOf(k.toUpperCase().replace('-', '_')); } catch (IllegalArgumentException e) { return; }
            RiskPolicy.TypePolicy d = RiskPolicy.defaultFor(t);
            merged.put(t, new RiskPolicy.TypePolicy(
                    o.hardLimit != null ? o.hardLimit : d.hardLimit(), o.reviewAmount != null ? o.reviewAmount : d.reviewAmount(),
                    o.stepUpAmount != null ? o.stepUpAmount : d.stepUpAmount(),
                    o.maxAttemptsPerHour != null ? o.maxAttemptsPerHour : d.maxAttemptsPerHour(),
                    o.failureLockThreshold != null ? o.failureLockThreshold : d.failureLockThreshold(),
                    o.stepUpScore != null ? o.stepUpScore : d.stepUpScore(), o.reviewScore != null ? o.reviewScore : d.reviewScore(),
                    o.blockScore != null ? o.blockScore : d.blockScore(), o.scoreCanBlock != null ? o.scoreCanBlock : d.scoreCanBlock()));
        });
        return new RiskPolicy(ruleSetVersion, m, Duration.ofMinutes(Math.max(1, validityMinutes)), merged);
    }

    /** Only PULA Ride may be switched off. Every other path is always protected. */
    public boolean isEnabledFor(RiskType t) { return t != RiskType.TAXI || taxiEnabled; }

    public String getMode() { return mode; } public void setMode(String v) { mode = v; }
    public String getRuleSetVersion() { return ruleSetVersion; } public void setRuleSetVersion(String v) { ruleSetVersion = v; }
    public int getValidityMinutes() { return validityMinutes; } public void setValidityMinutes(int v) { validityMinutes = v; }
    public boolean isTaxiEnabled() { return taxiEnabled; } public void setTaxiEnabled(boolean v) { taxiEnabled = v; }
    public Map<String, TypeOverride> getTypes() { return types; } public void setTypes(Map<String, TypeOverride> v) { types = v; }
}
