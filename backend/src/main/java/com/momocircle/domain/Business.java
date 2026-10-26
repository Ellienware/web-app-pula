package com.momocircle.domain;

import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A seller/storefront that a user runs on PULA Market. One user can own at most
 * one Business in this slice (a user -> many businesses relationship is a
 * trivial extension, not needed for the current product surface).
 */
@Entity
@Table(name = "businesses")
public class Business extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    private String logoUrl;

    @Column(nullable = false)
    private String category;

    /**
     * What kind of seller this is (formal retailer, local business, informal
     * trader, or service provider). Defaults to LOCAL_BUSINESS so existing
     * rows migrated before this field existed still get a sensible value —
     * see V8__business_type_fulfilment.sql.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BusinessType businessType = BusinessType.LOCAL_BUSINESS;

    /** General service area, e.g. "Douala" or "Kampala, Central Region". Not a precise address. */
    private String location;

    private String contactPhone;
    private String contactEmail;

    @ElementCollection
    private java.util.List<String> operatingHours = new java.util.ArrayList<>();

    /** How this seller can get orders to customers. Empty means not yet configured. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "business_fulfilment_options", joinColumns = @JoinColumn(name = "business_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_option", length = 32)
    private java.util.Set<FulfilmentOption> fulfilmentOptions = new java.util.LinkedHashSet<>();

    /**
     * What this business is allowed to transact on PULA (brief §6) — a business
     * may hold several at once (e.g. a spaza is both CAN_SELL_TO_CONSUMERS and
     * CAN_BUY_STOCK). Defaults to CAN_SELL_TO_CONSUMERS so every existing
     * business (all of which were consumer-facing sellers before this field
     * existed — see V16__business_capabilities.sql) keeps working unchanged.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "business_capabilities", joinColumns = @JoinColumn(name = "business_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", length = 32)
    private java.util.Set<BusinessCapability> capabilities = new java.util.LinkedHashSet<>(
            java.util.List.of(BusinessCapability.CAN_SELL_TO_CONSUMERS));

    /**
     * Self-declared, never verified against CIPC or any government registry
     * (brief §7 — PULA provides registration *readiness*, not real
     * registration). Feeds the profile-completeness indicator (§8) and
     * Funding Readiness (§19), never a lending decision.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 32)
    private BusinessRegistrationStatus registrationStatus = BusinessRegistrationStatus.NOT_REGISTERED;

    /** Optional, self-reported (e.g. a CIPC enterprise number the owner already has). Not validated against any registry. */
    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @Column(nullable = false)
    private boolean active = true;

    @Column(precision = 3, scale = 2)
    private BigDecimal ratingAverage = BigDecimal.ZERO;

    /**
     * Optional, seller-configured minimum order subtotal to qualify for
     * checkout at this business — a real "minimum basket requirement"
     * (brief §10), never inferred or estimated. Null means no minimum.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal minimumOrderAmount;

    /**
     * Optional, seller-configured flat delivery fee for SELLER_DELIVERY.
     * Real seller-provided data, not a computed estimate — MOMORIDE_DELIVERY
     * has its own fee on DeliveryRequest.estimatedFee instead (unset until
     * that feature computes real fees; never fabricated here either).
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal deliveryFee;

    @Column(nullable = false)
    private int ratingCount = 0;

    /** PULA Food: owner has temporarily paused orders (single-location businesses have no branch to close). */
    @Column(nullable = false)
    private boolean temporarilyClosed = false;

    /** PULA Food: minutes the restaurant has to accept a paid order. Null means the platform default. */
    private Integer acceptTimeoutMinutes;

    public boolean isTemporarilyClosed() { return temporarilyClosed; }
    public void setTemporarilyClosed(boolean temporarilyClosed) { this.temporarilyClosed = temporarilyClosed; }
    public Integer getAcceptTimeoutMinutes() { return acceptTimeoutMinutes; }
    public void setAcceptTimeoutMinutes(Integer acceptTimeoutMinutes) { this.acceptTimeoutMinutes = acceptTimeoutMinutes; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BusinessType getBusinessType() { return businessType; }
    public void setBusinessType(BusinessType businessType) { this.businessType = businessType; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public java.util.List<String> getOperatingHours() { return operatingHours; }
    public void setOperatingHours(java.util.List<String> operatingHours) { this.operatingHours = operatingHours; }
    public java.util.Set<FulfilmentOption> getFulfilmentOptions() { return fulfilmentOptions; }
    public void setFulfilmentOptions(java.util.Set<FulfilmentOption> fulfilmentOptions) { this.fulfilmentOptions = fulfilmentOptions; }
    public java.util.Set<BusinessCapability> getCapabilities() { return capabilities; }
    public void setCapabilities(java.util.Set<BusinessCapability> capabilities) { this.capabilities = capabilities; }
    public boolean hasCapability(BusinessCapability capability) { return capabilities.contains(capability); }
    public BusinessRegistrationStatus getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(BusinessRegistrationStatus registrationStatus) { this.registrationStatus = registrationStatus; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public BigDecimal getRatingAverage() { return ratingAverage; }
    public void setRatingAverage(BigDecimal ratingAverage) { this.ratingAverage = ratingAverage; }
    public BigDecimal getMinimumOrderAmount() { return minimumOrderAmount; }
    public void setMinimumOrderAmount(BigDecimal minimumOrderAmount) { this.minimumOrderAmount = minimumOrderAmount; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
}

