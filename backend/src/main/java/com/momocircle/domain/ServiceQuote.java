package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_quotes")
public class ServiceQuote extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "request_id", nullable = false)
    private ServiceRequest request;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false)
    private Business business;
    @Column(nullable = false)
    private int versionNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private ServiceQuoteStatus status = ServiceQuoteStatus.SUBMITTED;
    @Column(precision = 9, scale = 2)
    private BigDecimal hours;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal labourAmount;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal materialsAmount;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal deliveryAmount;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal otherAmount;
    @Column(length = 200)
    private String otherDescription;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal total;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false)
    private Instant validUntil;
    @Column(length = 1000)
    private String notes;
    @Column(length = 500)
    private String customerMessage;
    private Instant decidedAt;

    public ServiceRequest getRequest() { return request; }
    public void setRequest(ServiceRequest request) { this.request = request; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public ServiceQuoteStatus getStatus() { return status; }
    public void setStatus(ServiceQuoteStatus status) { this.status = status; }
    public BigDecimal getHours() { return hours; }
    public void setHours(BigDecimal hours) { this.hours = hours; }
    public BigDecimal getLabourAmount() { return labourAmount; }
    public void setLabourAmount(BigDecimal labourAmount) { this.labourAmount = labourAmount; }
    public BigDecimal getMaterialsAmount() { return materialsAmount; }
    public void setMaterialsAmount(BigDecimal materialsAmount) { this.materialsAmount = materialsAmount; }
    public BigDecimal getDeliveryAmount() { return deliveryAmount; }
    public void setDeliveryAmount(BigDecimal deliveryAmount) { this.deliveryAmount = deliveryAmount; }
    public BigDecimal getOtherAmount() { return otherAmount; }
    public void setOtherAmount(BigDecimal otherAmount) { this.otherAmount = otherAmount; }
    public String getOtherDescription() { return otherDescription; }
    public void setOtherDescription(String otherDescription) { this.otherDescription = otherDescription; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCustomerMessage() { return customerMessage; }
    public void setCustomerMessage(String customerMessage) { this.customerMessage = customerMessage; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
