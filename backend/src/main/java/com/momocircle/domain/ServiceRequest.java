package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_requests")
public class ServiceRequest extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id", nullable = false)
    private User customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "listing_id", nullable = false)
    private ServiceListing listing;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false)
    private Business business;
    @Column(nullable = false, length = 2000)
    private String description;
    @Column(nullable = false, length = 300)
    private String locationText;
    private LocalDate preferredDate;
    @Column(length = 40)
    private String preferredTime;
    @Column(length = 1000)
    private String requirements;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private MaterialsResponsibility materialsResponsibility = MaterialsResponsibility.NONE;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ServiceRequestStatus status = ServiceRequestStatus.DRAFT;
    @Column(length = 100)
    private String idempotencyKey;
    private Instant expiresAt;

    public User getCustomer() { return customer; }
    public void setCustomer(User customer) { this.customer = customer; }
    public ServiceListing getListing() { return listing; }
    public void setListing(ServiceListing listing) { this.listing = listing; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocationText() { return locationText; }
    public void setLocationText(String locationText) { this.locationText = locationText; }
    public LocalDate getPreferredDate() { return preferredDate; }
    public void setPreferredDate(LocalDate preferredDate) { this.preferredDate = preferredDate; }
    public String getPreferredTime() { return preferredTime; }
    public void setPreferredTime(String preferredTime) { this.preferredTime = preferredTime; }
    public String getRequirements() { return requirements; }
    public void setRequirements(String requirements) { this.requirements = requirements; }
    public MaterialsResponsibility getMaterialsResponsibility() { return materialsResponsibility; }
    public void setMaterialsResponsibility(MaterialsResponsibility materialsResponsibility) { this.materialsResponsibility = materialsResponsibility; }
    public ServiceRequestStatus getStatus() { return status; }
    public void setStatus(ServiceRequestStatus status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
