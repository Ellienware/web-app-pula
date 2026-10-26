package com.momocircle.domain;

import com.momocircle.domain.enums.IncomeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Root of the Money Confidence domain — one per user (see the unique
 * constraint on {@code user_id}). Holds only how the user describes their
 * own income; it deliberately does NOT duplicate data that already lives
 * elsewhere (Community Savings goals/contributions, MoMoMarket orders,
 * MoMoRide fares) — the insights layer (a later phase) reads those directly
 * rather than copying them in here. See the brief's own §15 instruction not
 * to duplicate existing entities.
 */
@Entity
@Table(name = "financial_profiles")
public class FinancialProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncomeType incomeType = IncomeType.VARIABLE_INCOME;

    /** ISO 4217 — the currency this user plans in. Defaults to the deployment's default currency at creation. */
    @Column(nullable = false, length = 3)
    private String currency;

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public IncomeType getIncomeType() { return incomeType; }
    public void setIncomeType(IncomeType incomeType) { this.incomeType = incomeType; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
