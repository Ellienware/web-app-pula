package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_bookings")
public class ServiceBooking extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "request_id", nullable = false)
    private ServiceRequest request;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "quote_id", nullable = false)
    private ServiceQuote quote;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id", nullable = false)
    private User customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false)
    private Business business;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "listing_id", nullable = false)
    private ServiceListing listing;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ServiceBookingStatus status = ServiceBookingStatus.BOOKED;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal total;
    @Column(nullable = false, length = 3)
    private String currency;
    private LocalDate scheduledDate;
    @Column(length = 40)
    private String scheduledTime;
    private Instant startedAt;
    private Instant completedAt;
    private Instant cancelledAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;
    @Column(length = 300)
    private String cancelReason;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_id")
    private Payment payment;
    @Column(nullable = false)
    private int paymentAttempts = 0;
    @Column(name = "save_to_buy_request_id", length = 36)
    private String saveToBuyRequestId;

    public ServiceRequest getRequest() { return request; }
    public void setRequest(ServiceRequest request) { this.request = request; }
    public ServiceQuote getQuote() { return quote; }
    public void setQuote(ServiceQuote quote) { this.quote = quote; }
    public User getCustomer() { return customer; }
    public void setCustomer(User customer) { this.customer = customer; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public ServiceListing getListing() { return listing; }
    public void setListing(ServiceListing listing) { this.listing = listing; }
    public ServiceBookingStatus getStatus() { return status; }
    public void setStatus(ServiceBookingStatus status) { this.status = status; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDate scheduledDate) { this.scheduledDate = scheduledDate; }
    public String getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(String scheduledTime) { this.scheduledTime = scheduledTime; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public User getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(User cancelledBy) { this.cancelledBy = cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public int getPaymentAttempts() { return paymentAttempts; }
    public void setPaymentAttempts(int paymentAttempts) { this.paymentAttempts = paymentAttempts; }
    public String getSaveToBuyRequestId() { return saveToBuyRequestId; }
    public void setSaveToBuyRequestId(String saveToBuyRequestId) { this.saveToBuyRequestId = saveToBuyRequestId; }
}
