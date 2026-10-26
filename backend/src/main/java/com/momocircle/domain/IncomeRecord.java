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
 * A single income entry — "I received R800 on 3 March from casual labour".
 * The irregular-income planner (IncomeRecordService#weeklySummary) buckets
 * these by calendar week/month on read rather than requiring the user to
 * pre-label entries as "Week 1"/"Week 2" — real income doesn't arrive on a
 * tidy schedule, and hard-coded period labels would force it into one. See
 * the brief's §16 instruction to work from historical/actual income, not
 * assumed regularity.
 */
@Entity
@Table(name = "income_records")
public class IncomeRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private LocalDate receivedDate;

    /** Free text, e.g. "Casual labour", "Salary", "PULA Market sales". Optional. */
    private String source;

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDate receivedDate) { this.receivedDate = receivedDate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
