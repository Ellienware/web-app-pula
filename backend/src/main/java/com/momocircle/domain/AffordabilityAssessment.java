package com.momocircle.domain;

import com.momocircle.domain.enums.AffordabilityResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A stored "can I afford this?" check (brief §17). Kept so a user can see
 * their own history of checks, and so the AI assistant (a later phase) can
 * reference a specific past assessment by id rather than re-deriving one.
 * Read-only for the user who ran it — see MoneyController for the
 * ownership scoping.
 */
@Entity
@Table(name = "affordability_assessments")
public class AffordabilityAssessment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** What the user was checking, e.g. "New school shoes", "Full Cream Milk 2L basket". Optional. */
    private String context;

    /** Free-text budget-category name the user says this would fall under, if any. Optional. */
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AffordabilityResult result;

    @Column(nullable = false, length = 1000)
    private String reasoning;

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public AffordabilityResult getResult() { return result; }
    public void setResult(AffordabilityResult result) { this.result = result; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
