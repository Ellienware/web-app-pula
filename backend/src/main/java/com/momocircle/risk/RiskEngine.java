package com.momocircle.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, explainable, dependency-free rules.
 *
 * Precedence (documented and tested):
 *   1. HARD rules, in order: ownership, inactive account, inactive recipient, insufficient funds, hard amount limit -> BLOCK;
 *      repeated failures at the lock threshold -> REVIEW. The first hard rule that fires decides; the score is still computed
 *      for the audit trail but cannot downgrade it.
 *   2. SOFT signals add points (capped at 100), mapped to a decision by the type's score bands.
 * Missing signals add nothing and are listed as unavailable.
 */
public final class RiskEngine {

    private RiskEngine() {}

    public static RiskResult evaluate(RiskContext c, RiskPolicy policy) {
        RiskPolicy.TypePolicy p = policy.forType(c.type());
        List<String> rules = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        int score = softScore(c, p, rules, unavailable);

        // ---- 1. hard rules ----
        if (!c.ownershipValid()) return hard("H_OWNERSHIP_MISMATCH", RiskDecision.BLOCK, score, rules, unavailable);
        if (Boolean.FALSE.equals(c.userActive())) return hard("H_ACCOUNT_INACTIVE", RiskDecision.BLOCK, score, rules, unavailable);
        if (Boolean.FALSE.equals(c.recipientActive())) return hard("H_RECIPIENT_INACTIVE", RiskDecision.BLOCK, score, rules, unavailable);
        if (Boolean.FALSE.equals(c.fundsSufficient())) return hard("H_INSUFFICIENT_FUNDS", RiskDecision.BLOCK, score, rules, unavailable);
        if (c.amount().compareTo(p.hardLimit()) > 0) return hard("H_AMOUNT_OVER_HARD_LIMIT", RiskDecision.BLOCK, score, rules, unavailable);
        if (c.failuresLast24h() != null && c.failuresLast24h() >= p.failureLockThreshold()) {
            return hard("H_REPEATED_FAILURES", RiskDecision.REVIEW, score, rules, unavailable);
        }

        // ---- 2. score bands ----
        RiskDecision d;
        if (p.scoreCanBlock() && score >= p.blockScore()) d = RiskDecision.BLOCK;
        else if (score >= p.reviewScore()) d = RiskDecision.REVIEW;
        else if (score >= p.stepUpScore()) d = RiskDecision.STEP_UP;
        else d = RiskDecision.ALLOW;
        return new RiskResult(score, levelFor(score, p), d, List.copyOf(rules), List.copyOf(unavailable), explain(d), false);
    }

    private static int softScore(RiskContext c, RiskPolicy.TypePolicy p, List<String> rules, List<String> unavailable) {
        int s = 0;
        // amount
        if (c.amount().compareTo(p.reviewAmount()) > 0) { s += 40; rules.add("S_AMOUNT_VERY_HIGH"); }
        else if (c.amount().compareTo(p.stepUpAmount()) > 0) { s += 25; rules.add("S_AMOUNT_HIGH"); }
        // frequency (from the risk layer's own attempt history)
        if (c.attemptsLastHour() == null) unavailable.add("FREQUENCY");
        else if (c.attemptsLastHour() >= 2 * p.maxAttemptsPerHour()) { s += 45; rules.add("S_FREQUENCY_VERY_HIGH"); }
        else if (c.attemptsLastHour() >= p.maxAttemptsPerHour()) { s += 30; rules.add("S_FREQUENCY_HIGH"); }
        // failures
        if (c.failuresLast24h() == null) unavailable.add("FAILURES");
        else if (c.failuresLast24h() >= 2) { s += 20; rules.add("S_RECENT_FAILURES"); }
        // pattern deviation: only with enough history
        if (c.historyCount() == null || c.historyAverage() == null || c.historyCount() < 5) {
            unavailable.add("PATTERN_HISTORY");
        } else if (c.historyAverage().signum() > 0 && c.amount().compareTo(c.historyAverage().multiply(BigDecimal.valueOf(3))) >= 0) {
            s += 25; rules.add("S_PATTERN_DEVIATION");
        }
        // recipient verification: not collected by PULA today
        if (c.recipientVerified() == null) unavailable.add("RECIPIENT_VERIFICATION");
        else if (!c.recipientVerified()) { s += 15; rules.add("S_RECIPIENT_UNVERIFIED"); }
        // ScamShield user-run assessments
        if (c.recentHighScamAlerts() == null) unavailable.add("SCAMSHIELD");
        else if (c.recentHighScamAlerts() > 0) { s += 20; rules.add("S_RECENT_SCAM_ALERT"); }
        // first payment to this recipient, only meaningful when the amount is not small
        if (c.firstTimeRecipient() != null && c.firstTimeRecipient()
                && c.amount().compareTo(p.stepUpAmount().divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)) > 0) {
            s += 10; rules.add("S_NEW_RECIPIENT");
        }
        return Math.min(100, s);
    }

    private static RiskResult hard(String rule, RiskDecision d, int score, List<String> soft, List<String> unavailable) {
        List<String> all = new ArrayList<>();
        all.add(rule);
        all.addAll(soft);
        RiskLevel l = d == RiskDecision.BLOCK ? RiskLevel.CRITICAL : RiskLevel.HIGH;
        return new RiskResult(score, l, d, List.copyOf(all), List.copyOf(unavailable), explain(d), true);
    }

    private static RiskLevel levelFor(int score, RiskPolicy.TypePolicy p) {
        if (score >= p.reviewScore()) return RiskLevel.HIGH;
        if (score >= p.stepUpScore()) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** Proportionate, non-accusatory, and deliberately silent about thresholds and which signal fired. */
    public static String explain(RiskDecision d) {
        return switch (d) {
            case ALLOW -> "This payment can go ahead.";
            case STEP_UP -> "For your protection, please confirm this payment's amount again before we continue.";
            case REVIEW -> "This payment is being checked before it can continue. We will let you know the outcome.";
            case BLOCK -> "We could not process this payment. Please check the details or contact support.";
        };
    }
}
