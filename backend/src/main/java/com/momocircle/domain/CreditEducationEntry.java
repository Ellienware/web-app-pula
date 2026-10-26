package com.momocircle.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A stored result of the responsible-credit educational calculator (brief
 * §18). This is deliberately NOT a loan, application, or offer — MoMoCircle
 * is not a lender and this entity records nothing more than "if a loan with
 * these terms existed, here's what it would actually cost," for the user's
 * own reference. See CreditEducationService's javadoc for the calculation
 * and the warning heuristics.
 */
@Entity
@Table(name = "credit_education_entries")
public class CreditEducationEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal annualInterestRatePercent;

    @Column(nullable = false)
    private int termMonths;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fees;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalRepayment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalInterest;

    /** How much more than the principal the total repayment (incl. fees) works out to, as a percentage. */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal totalCostPercent;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "credit_education_warnings", joinColumns = @JoinColumn(name = "entry_id"))
    @Column(name = "warning", length = 500)
    private List<String> warnings = new ArrayList<>();

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BigDecimal getPrincipal() { return principal; }
    public void setPrincipal(BigDecimal principal) { this.principal = principal; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getAnnualInterestRatePercent() { return annualInterestRatePercent; }
    public void setAnnualInterestRatePercent(BigDecimal annualInterestRatePercent) { this.annualInterestRatePercent = annualInterestRatePercent; }
    public int getTermMonths() { return termMonths; }
    public void setTermMonths(int termMonths) { this.termMonths = termMonths; }
    public BigDecimal getFees() { return fees; }
    public void setFees(BigDecimal fees) { this.fees = fees; }
    public BigDecimal getMonthlyPayment() { return monthlyPayment; }
    public void setMonthlyPayment(BigDecimal monthlyPayment) { this.monthlyPayment = monthlyPayment; }
    public BigDecimal getTotalRepayment() { return totalRepayment; }
    public void setTotalRepayment(BigDecimal totalRepayment) { this.totalRepayment = totalRepayment; }
    public BigDecimal getTotalInterest() { return totalInterest; }
    public void setTotalInterest(BigDecimal totalInterest) { this.totalInterest = totalInterest; }
    public BigDecimal getTotalCostPercent() { return totalCostPercent; }
    public void setTotalCostPercent(BigDecimal totalCostPercent) { this.totalCostPercent = totalCostPercent; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
