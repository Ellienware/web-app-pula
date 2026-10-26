package com.momocircle.risk;

/** Result of an assessment call. {@code failureCode} is null when the payment may proceed. */
public record AssessmentOutcome(String assessmentId, String intentId, RiskDecision decision, String failureCode,
                                String safeMessage, boolean shadow) {
    public boolean allowed() { return failureCode == null; }
}
