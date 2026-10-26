package com.momocircle.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {

    private final RiskPolicy policy = RiskPolicy.defaults();

    private static RiskContext ctx(RiskType t, String amount) {
        return new RiskContext(t, new BigDecimal(amount), "ZAR", true, true, true, null, null, 0, 0, null, null, false, 0, false);
    }
    private static RiskContext with(RiskContext c, java.util.function.UnaryOperator<Builder> f) { return f.apply(new Builder(c)).build(); }

    /** Tiny copy-builder so tests change one signal at a time. */
    static final class Builder {
        RiskType type; BigDecimal amount; String cur; boolean own; Boolean ua, ra, rv, fs; Integer att, fail, hc; BigDecimal ha; Boolean ftr; Integer scam; boolean sim;
        Builder(RiskContext c) { type=c.type(); amount=c.amount(); cur=c.currency(); own=c.ownershipValid(); ua=c.userActive(); ra=c.recipientActive(); rv=c.recipientVerified();
            fs=c.fundsSufficient(); att=c.attemptsLastHour(); fail=c.failuresLast24h(); hc=c.historyCount(); ha=c.historyAverage(); ftr=c.firstTimeRecipient(); scam=c.recentHighScamAlerts(); sim=c.simulated(); }
        Builder own(boolean v){own=v;return this;} Builder ua(Boolean v){ua=v;return this;} Builder ra(Boolean v){ra=v;return this;} Builder fs(Boolean v){fs=v;return this;}
        Builder att(Integer v){att=v;return this;} Builder fail(Integer v){fail=v;return this;} Builder hist(Integer n, String avg){hc=n;ha=avg==null?null:new BigDecimal(avg);return this;}
        Builder scam(Integer v){scam=v;return this;} Builder ftr(Boolean v){ftr=v;return this;} Builder rv(Boolean v){rv=v;return this;}
        RiskContext build(){ return new RiskContext(type, amount, cur, own, ua, ra, rv, fs, att, fail, hc, ha, ftr, scam, sim); }
    }

    @Test
    void ordinarySmallPaymentIsAllowedWithLowRisk() {
        RiskResult r = RiskEngine.evaluate(ctx(RiskType.CONSUMER_CHECKOUT, "150.00"), policy);
        assertEquals(RiskDecision.ALLOW, r.decision());
        assertEquals(RiskLevel.LOW, r.level());
        assertEquals(0, r.score());
        assertFalse(r.hardRule());
    }

    @Test
    void scoreIsBoundedZeroToOneHundred() {
        RiskContext c = with(ctx(RiskType.CONSUMER_CHECKOUT, "9999.00"), b -> b.att(100).fail(1).hist(10, "10").scam(3).rv(false).ftr(true));
        RiskResult r = RiskEngine.evaluate(c, policy);
        assertTrue(r.score() >= 0 && r.score() <= 100);
    }

    @Test
    void hardRulesBlockRegardlessOfAnOtherwiseCleanScore() {
        assertEquals("H_OWNERSHIP_MISMATCH", RiskEngine.evaluate(with(ctx(RiskType.STOCK_ORDER, "10"), b -> b.own(false)), policy).ruleIds().get(0));
        assertEquals(RiskDecision.BLOCK, RiskEngine.evaluate(with(ctx(RiskType.STOCK_ORDER, "10"), b -> b.own(false)), policy).decision());
        assertEquals("H_ACCOUNT_INACTIVE", RiskEngine.evaluate(with(ctx(RiskType.STOCK_ORDER, "10"), b -> b.ua(false)), policy).ruleIds().get(0));
        assertEquals("H_RECIPIENT_INACTIVE", RiskEngine.evaluate(with(ctx(RiskType.STOCK_ORDER, "10"), b -> b.ra(false)), policy).ruleIds().get(0));
        assertEquals("H_INSUFFICIENT_FUNDS", RiskEngine.evaluate(with(ctx(RiskType.STOCK_ORDER, "10"), b -> b.fs(false)), policy).ruleIds().get(0));
        RiskResult over = RiskEngine.evaluate(ctx(RiskType.TAXI, "2000.01"), policy);
        assertEquals(RiskDecision.BLOCK, over.decision());
        assertEquals("H_AMOUNT_OVER_HARD_LIMIT", over.ruleIds().get(0));
        assertTrue(over.hardRule());
        assertEquals(RiskLevel.CRITICAL, over.level());
    }

    @Test
    void hardRulePrecedenceOwnershipBeatsEverythingElse() {
        RiskContext c = with(ctx(RiskType.TAXI, "99999"), b -> b.own(false).ua(false).ra(false).fs(false));
        assertEquals("H_OWNERSHIP_MISMATCH", RiskEngine.evaluate(c, policy).ruleIds().get(0));
    }

    @Test
    void hardLimitIsInclusiveBoundary() {
        assertNotEquals(RiskDecision.BLOCK, RiskEngine.evaluate(ctx(RiskType.TAXI, "2000.00"), policy).decision());
    }

    @Test
    void repeatedFailuresHoldForReviewNotBlock() {
        RiskResult r = RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "100"), b -> b.fail(3)), policy);
        assertEquals(RiskDecision.REVIEW, r.decision());
        assertEquals("H_REPEATED_FAILURES", r.ruleIds().get(0));
    }

    @Test
    void amountBandsMapToStepUpThenReviewButNeverBlockOnScoreAlone() {
        assertEquals(RiskDecision.ALLOW, RiskEngine.evaluate(ctx(RiskType.CONSUMER_CHECKOUT, "3000.00"), policy).decision());   // not above stepUpAmount
        RiskResult stepUp = RiskEngine.evaluate(ctx(RiskType.CONSUMER_CHECKOUT, "3000.01"), policy);                         // +25 only
        assertEquals(RiskDecision.ALLOW, stepUp.decision());
        RiskResult review = RiskEngine.evaluate(ctx(RiskType.CONSUMER_CHECKOUT, "10000.01"), policy);                        // +40 -> step-up band
        assertEquals(RiskDecision.STEP_UP, review.decision());
        RiskContext piled = with(ctx(RiskType.CONSUMER_CHECKOUT, "10000.01"), b -> b.att(16).scam(1).ftr(true).fail(2));
        RiskResult high = RiskEngine.evaluate(piled, policy);
        assertEquals(RiskDecision.REVIEW, high.decision());
        assertNotEquals(RiskDecision.BLOCK, high.decision(), "a score alone must not block by default");
        assertFalse(high.hardRule());
    }

    @Test
    void scoreCanBlockOnlyWhenExplicitlyConfigured() {
        RiskPolicy.TypePolicy base = RiskPolicy.defaultFor(RiskType.CONSUMER_CHECKOUT);
        RiskPolicy.TypePolicy blocking = new RiskPolicy.TypePolicy(base.hardLimit(), base.reviewAmount(), base.stepUpAmount(), base.maxAttemptsPerHour(),
                base.failureLockThreshold(), 40, 70, 90, true);
        RiskPolicy p = new RiskPolicy("t", RiskPolicy.Mode.ENFORCE, java.time.Duration.ofMinutes(5), Map.of(RiskType.CONSUMER_CHECKOUT, blocking));
        RiskContext c = with(ctx(RiskType.CONSUMER_CHECKOUT, "10000.01"), b -> b.att(16).scam(1).ftr(true).fail(2).hist(10, "100"));
        RiskResult r = RiskEngine.evaluate(c, p);
        assertEquals(100, r.score());
        assertEquals(RiskDecision.BLOCK, r.decision());
        assertFalse(r.hardRule());
    }

    @Test
    void frequencyThresholdsScaleWithPolicy() {
        int max = RiskPolicy.defaultFor(RiskType.CONSUMER_CHECKOUT).maxAttemptsPerHour();
        assertTrue(RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "100"), b -> b.att(max - 1)), policy).ruleIds().isEmpty());
        assertTrue(RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "100"), b -> b.att(max)), policy).ruleIds().contains("S_FREQUENCY_HIGH"));
        assertTrue(RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "100"), b -> b.att(2 * max)), policy).ruleIds().contains("S_FREQUENCY_VERY_HIGH"));
    }

    @Test
    void patternDeviationNeedsEnoughHistoryAndOtherwiseIsReportedUnavailable() {
        RiskResult none = RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "1000"), b -> b.hist(2, "100")), policy);
        assertFalse(none.ruleIds().contains("S_PATTERN_DEVIATION"));
        assertTrue(none.unavailableSignals().contains("PATTERN_HISTORY"));
        RiskResult some = RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "1000"), b -> b.hist(8, "100")), policy);
        assertTrue(some.ruleIds().contains("S_PATTERN_DEVIATION"));
        assertFalse(some.unavailableSignals().contains("PATTERN_HISTORY"));
    }

    @Test
    void missingSignalsAreNeutralAndListedNeverInvented() {
        RiskContext c = new RiskContext(RiskType.STOCK_ORDER, new BigDecimal("100"), "ZAR", true, null, null, null, null, null, null, null, null, null, null, false);
        RiskResult r = RiskEngine.evaluate(c, policy);
        assertEquals(RiskDecision.ALLOW, r.decision());
        assertEquals(0, r.score());
        for (String s : new String[]{"FREQUENCY", "FAILURES", "PATTERN_HISTORY", "RECIPIENT_VERIFICATION", "SCAMSHIELD"}) {
            assertTrue(r.unavailableSignals().contains(s), s);
        }
    }

    @Test
    void scamShieldAlertRaisesScoreButIsNotAccusatoryInTheExplanation() {
        RiskResult r = RiskEngine.evaluate(with(ctx(RiskType.CONSUMER_CHECKOUT, "3000.01"), b -> b.scam(1)), policy); // 25 + 20 = 45
        assertTrue(r.ruleIds().contains("S_RECENT_SCAM_ALERT"));
        assertEquals(RiskDecision.STEP_UP, r.decision());
        String text = r.safeExplanation().toLowerCase();
        for (String bad : new String[]{"fraud", "scam", "suspicious", "criminal", "threshold", "score"}) assertFalse(text.contains(bad), bad);
    }

    @Test
    void unusualAmountAloneNeverImpliesMerchantIsFraudulent() {
        RiskResult r = RiskEngine.evaluate(ctx(RiskType.STOCK_ORDER, "120000"), policy);
        for (String id : r.ruleIds()) assertFalse(id.contains("MERCHANT_FRAUD"));
        for (RiskDecision d : RiskDecision.values()) assertFalse(RiskEngine.explain(d).toLowerCase().contains("fraud"));
    }

    @Test
    void explanationsNeverRevealThresholdsOrRuleNames() {
        for (RiskDecision d : RiskDecision.values()) {
            String t = RiskEngine.explain(d);
            assertFalse(t.matches(".*\\d.*"), "no numbers in user text: " + t);
            assertFalse(t.contains("H_") || t.contains("S_"));
        }
    }

    @Test
    void taxiPolicyIsSeparateFromMarketplacePolicy() {
        assertTrue(RiskPolicy.defaultFor(RiskType.TAXI).hardLimit().compareTo(RiskPolicy.defaultFor(RiskType.STOCK_ORDER).hardLimit()) < 0);
    }

    @Test
    void sameInputGivesSameOutputDeterministically() {
        RiskContext c = with(ctx(RiskType.CONSUMER_CHECKOUT, "5000"), b -> b.att(9).scam(1));
        assertEquals(RiskEngine.evaluate(c, policy), RiskEngine.evaluate(c, policy));
    }
}
