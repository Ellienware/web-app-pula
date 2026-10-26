package com.momocircle.risk;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** One immutable engine verdict (plus the later step-up / review outcome). Bound to exactly one transaction intent. */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment extends BaseEntity {

    @Column(name = "intent_id", nullable = false, length = 36, updatable = false)
    private String intentId;
    @Column(name = "user_id", nullable = false, length = 36, updatable = false)
    private String userId;
    @Column(name = "business_id", length = 36, updatable = false)
    private String businessId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private RiskType type;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Column(nullable = false, updatable = false)
    private int score;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private RiskLevel level;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private RiskDecision decision;
    @Column(name = "rule_ids", length = 600, updatable = false)
    private String ruleIds;
    @Column(name = "unavailable_signals", length = 300, updatable = false)
    private String unavailableSignals;
    @Column(length = 300, updatable = false)
    private String explanation;
    @Column(name = "rule_set_version", nullable = false, length = 40, updatable = false)
    private String ruleSetVersion;
    /** ENFORCE or SHADOW at assessment time. A SHADOW assessment never holds or blocks. */
    @Column(nullable = false, length = 8, updatable = false)
    private String mode;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(nullable = false, updatable = false)
    private boolean simulated;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 12)
    private ReviewStatus reviewStatus = ReviewStatus.NONE;
    @Enumerated(EnumType.STRING)
    @Column(name = "step_up_status", nullable = false, length = 14)
    private StepUpStatus stepUpStatus = StepUpStatus.NOT_REQUIRED;
    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "review_note", length = 300)
    private String reviewNote;
    @Column(name = "step_up_confirmed_at")
    private Instant stepUpConfirmedAt;

    /** True only when this verdict currently permits the payment to proceed. Expired verdicts never do. */
    public boolean effectivelyAllowed(Instant now) {
        return failureCode(now) == null;
    }

    /** null when allowed, otherwise a stable code the API and frontends map to a message. */
    public String failureCode(Instant now) {
        if (!now.isBefore(expiresAt)) return "RISK_ASSESSMENT_EXPIRED";
        if ("SHADOW".equals(mode)) return null;
        return switch (decision) {
            case ALLOW -> null;
            case STEP_UP -> stepUpStatus == StepUpStatus.CONFIRMED ? null : "RISK_STEP_UP_REQUIRED";
            case REVIEW -> switch (reviewStatus) {
                case APPROVED -> null;
                case REJECTED -> "RISK_BLOCKED";
                default -> "RISK_REVIEW_PENDING";
            };
            case BLOCK -> "RISK_BLOCKED";
        };
    }

    public String getIntentId() { return intentId; }
    public void setIntentId(String v) { this.intentId = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String v) { this.businessId = v; }
    public RiskType getType() { return type; }
    public void setType(RiskType v) { this.type = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public int getScore() { return score; }
    public void setScore(int v) { this.score = v; }
    public RiskLevel getLevel() { return level; }
    public void setLevel(RiskLevel v) { this.level = v; }
    public RiskDecision getDecision() { return decision; }
    public void setDecision(RiskDecision v) { this.decision = v; }
    public String getRuleIds() { return ruleIds; }
    public void setRuleIds(String v) { this.ruleIds = v; }
    public String getUnavailableSignals() { return unavailableSignals; }
    public void setUnavailableSignals(String v) { this.unavailableSignals = v; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String v) { this.explanation = v; }
    public String getRuleSetVersion() { return ruleSetVersion; }
    public void setRuleSetVersion(String v) { this.ruleSetVersion = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public boolean isSimulated() { return simulated; }
    public void setSimulated(boolean v) { this.simulated = v; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(ReviewStatus v) { this.reviewStatus = v; }
    public StepUpStatus getStepUpStatus() { return stepUpStatus; }
    public void setStepUpStatus(StepUpStatus v) { this.stepUpStatus = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public Instant getStepUpConfirmedAt() { return stepUpConfirmedAt; }
    public void setStepUpConfirmedAt(Instant v) { this.stepUpConfirmedAt = v; }
}
