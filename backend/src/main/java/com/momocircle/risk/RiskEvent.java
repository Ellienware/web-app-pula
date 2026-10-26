package com.momocircle.risk;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Append-only audit trail for an assessment: decision, step-up, review, bound reference, initiation outcome. */
@Entity
@Table(name = "risk_events")
public class RiskEvent extends BaseEntity {
    @Column(name = "assessment_id", nullable = false, length = 36, updatable = false)
    private String assessmentId;
    @Column(name = "intent_id", nullable = false, length = 36, updatable = false)
    private String intentId;
    @Column(nullable = false, length = 40, updatable = false)
    private String type;
    @Column(name = "actor_user_id", length = 36, updatable = false)
    private String actorUserId;
    @Column(length = 300, updatable = false)
    private String detail;

    public String getAssessmentId() { return assessmentId; }
    public void setAssessmentId(String v) { this.assessmentId = v; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String v) { this.intentId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String v) { this.actorUserId = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
}
