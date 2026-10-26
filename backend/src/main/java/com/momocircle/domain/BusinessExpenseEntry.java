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
 * A manually-entered expense record for a Business (brief §16). Categories
 * match the brief's §16 breakdown: STOCK, DELIVERY, OPERATING, OTHER.
 * PULA auto-derives stock and delivery spend from StockOrders at query
 * time; this entity covers expenses not captured by any PULA transaction
 * (e.g. packaging, signage, utility bills).
 */
@Entity
@Table(name = "business_expense_entries")
public class BusinessExpenseEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    @Column(nullable = false)
    private LocalDate incurredDate;

    @Column(length = 500)
    private String description;

    /** STOCK | DELIVERY | OPERATING | OTHER — brief §16. */
    @Column(nullable = false, length = 32)
    private String category = "OTHER";

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getIncurredDate() { return incurredDate; }
    public void setIncurredDate(LocalDate incurredDate) { this.incurredDate = incurredDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
