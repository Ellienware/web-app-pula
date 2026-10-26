package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manually-entered income record for a Business (brief §16). Separate from
 * the existing {@link IncomeRecord} (which is consumer/personal and scoped to
 * a {@link User}) — these are scoped to a {@link Business} for the PULA Cash
 * Flow feature. PULA Market sales from {@link Order} are auto-derived at
 * query time; this entity covers income that happened outside PULA.
 */
@Entity
@Table(name = "business_income_entries")
public class BusinessIncomeEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    @Column(nullable = false)
    private LocalDate receivedDate;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String category;

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDate receivedDate) { this.receivedDate = receivedDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
