package com.momocircle.risk;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * What the user is about to pay for. The {@code fingerprint} is a hash of the material details (type, user, amount, currency,
 * recipient). Any change produces a different fingerprint and therefore a NEW intent that needs a NEW assessment.
 * Stores no device data and no card/phone numbers.
 */
@Entity
@Table(name = "transaction_intents",
        uniqueConstraints = @UniqueConstraint(name = "uq_intent_user_type_ref_fp", columnNames = {"user_id", "type", "reference_key", "fingerprint"}))
public class TransactionIntent extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, updatable = false)
    private String userId;
    @Column(name = "business_id", length = 36, updatable = false)
    private String businessId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private RiskType type;
    @Column(name = "reference_key", nullable = false, length = 120, updatable = false)
    private String referenceKey;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Column(name = "recipient_key", length = 80, updatable = false)
    private String recipientKey;
    @Column(nullable = false, length = 64, updatable = false)
    private String fingerprint;
    @Column(nullable = false, updatable = false)
    private boolean simulated;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IntentStatus status = IntentStatus.ASSESSED;
    @Column(name = "payment_reference", length = 120)
    private String paymentReference;
    @Column(name = "outcome_detail", length = 200)
    private String outcomeDetail;

    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String v) { this.businessId = v; }
    public RiskType getType() { return type; }
    public void setType(RiskType v) { this.type = v; }
    public String getReferenceKey() { return referenceKey; }
    public void setReferenceKey(String v) { this.referenceKey = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getRecipientKey() { return recipientKey; }
    public void setRecipientKey(String v) { this.recipientKey = v; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { this.fingerprint = v; }
    public boolean isSimulated() { return simulated; }
    public void setSimulated(boolean v) { this.simulated = v; }
    public IntentStatus getStatus() { return status; }
    public void setStatus(IntentStatus v) { this.status = v; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String v) { this.paymentReference = v; }
    public String getOutcomeDetail() { return outcomeDetail; }
    public void setOutcomeDetail(String v) { this.outcomeDetail = v; }
}
