package com.momocircle.domain;

import com.momocircle.domain.enums.ScamRiskLevel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A stored ScamShield check (brief §19). {@code submittedText} is ALWAYS
 * the redacted version, never the raw text the user pasted — see
 * ScamShieldService#redact, which runs before this entity is ever
 * constructed. This app must never persist a real OTP, PIN, password, or
 * similar secret even if a user accidentally includes one in a forwarded
 * message.
 */
@Entity
@Table(name = "scam_assessments")
public class ScamAssessment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 5000)
    private String submittedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScamRiskLevel riskLevel;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "scam_assessment_indicators", joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "indicator", length = 500)
    private List<String> indicators = new ArrayList<>();

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getSubmittedText() { return submittedText; }
    public void setSubmittedText(String submittedText) { this.submittedText = submittedText; }
    public ScamRiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(ScamRiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public List<String> getIndicators() { return indicators; }
    public void setIndicators(List<String> indicators) { this.indicators = indicators; }
}
