package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.momocircle.domain.enums.PaymentStatus;

import java.math.BigDecimal;

/**
 * A digital receipt for one Payment. Deliberately provider/domain-agnostic —
 * it works the same whether the underlying Payment backed a circle
 * Contribution or a MoMoMarket Order — so there's exactly one receipt
 * pipeline for the whole platform instead of one per feature.
 */
@Entity
@Table(name = "receipts")
public class Receipt extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String receiptNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payer_id", nullable = false)
    private User payer;

    /** Free-text label for the counterparty — a circle name or a business name. Not every recipient is a User. */
    @Column(nullable = false)
    private String payeeLabel;

    /** e.g. "Contribution to Durban Holiday" or "Order at ABC Catering (3 items)". */
    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String providerReference;

    public String getReceiptNumber() { return receiptNumber; }
    public void setReceiptNumber(String receiptNumber) { this.receiptNumber = receiptNumber; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public User getPayer() { return payer; }
    public void setPayer(User payer) { this.payer = payer; }
    public String getPayeeLabel() { return payeeLabel; }
    public void setPayeeLabel(String payeeLabel) { this.payeeLabel = payeeLabel; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
}
