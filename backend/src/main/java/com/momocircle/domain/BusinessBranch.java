package com.momocircle.domain;

import com.momocircle.domain.enums.FulfilmentOption;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PULA Food: one physical location of a food business. Optional — a business with no
 * branch rows is a single location and uses the Business columns directly. Latitude and
 * longitude are set by the merchant (map pin / device GPS), never geocoded silently.
 */
@Entity
@Table(name = "business_branches")
public class BusinessBranch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String address;

    /** Suburb/township used for "near Soweto" style searches. */
    private String area;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    /** General branch fields (Phase 9C). All optional so a small trader is never forced to fill them in. */
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String province;

    @Column(length = 10)
    private String postalCode;

    @Column(length = 20)
    private String phone;

    private String email;

    /** Authoritative operational status. {@code active} below mirrors {@code status == ACTIVE} for the existing Food code. */
    @jakarta.persistence.Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private com.momocircle.branch.BranchStatus status = com.momocircle.branch.BranchStatus.ACTIVE;

    /** Always UNVERIFIED today: PULA has no branch/address verification process. Stored so a real one can be added later. */
    @Column(nullable = false, length = 16)
    private String verificationStatus = "UNVERIFIED";

    /** Franchise modelling: descriptive and unverified. Same-brand outlets are NOT assumed to share a legal entity. */
    private String brandName;

    @jakarta.persistence.Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private com.momocircle.branch.OperatingModel operatingModel = com.momocircle.branch.OperatingModel.OWNED;

    private String operatorName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean temporarilyClosed = false;

    private Integer prepTimeMinutes;

    @Column(precision = 19, scale = 2)
    private BigDecimal deliveryFee;

    @Column(precision = 19, scale = 2)
    private BigDecimal minimumOrderAmount;

    @ElementCollection
    @CollectionTable(name = "branch_operating_hours", joinColumns = @JoinColumn(name = "branch_id"))
    @Column(name = "operating_hours")
    private List<String> operatingHours = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "branch_fulfilment_options", joinColumns = @JoinColumn(name = "branch_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_option", length = 32)
    private Set<FulfilmentOption> fulfilmentOptions = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "branch_delivery_areas", joinColumns = @JoinColumn(name = "branch_id"))
    @Column(name = "area")
    private Set<String> deliveryAreas = new LinkedHashSet<>();

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public com.momocircle.branch.OperatingModel getOperatingModel() { return operatingModel; }
    public void setOperatingModel(com.momocircle.branch.OperatingModel operatingModel) { this.operatingModel = operatingModel; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public com.momocircle.branch.BranchStatus getStatus() { return status; }
    public String getVerificationStatus() { return verificationStatus; }

    /** Sets the status and keeps the legacy {@code active} flag in step (active == ACTIVE). Transition rules are the caller's. */
    public void applyStatus(com.momocircle.branch.BranchStatus newStatus) {
        this.status = newStatus;
        this.active = newStatus == com.momocircle.branch.BranchStatus.ACTIVE;
    }

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isTemporarilyClosed() { return temporarilyClosed; }
    public void setTemporarilyClosed(boolean temporarilyClosed) { this.temporarilyClosed = temporarilyClosed; }
    public Integer getPrepTimeMinutes() { return prepTimeMinutes; }
    public void setPrepTimeMinutes(Integer prepTimeMinutes) { this.prepTimeMinutes = prepTimeMinutes; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }
    public BigDecimal getMinimumOrderAmount() { return minimumOrderAmount; }
    public void setMinimumOrderAmount(BigDecimal minimumOrderAmount) { this.minimumOrderAmount = minimumOrderAmount; }
    public List<String> getOperatingHours() { return operatingHours; }
    public void setOperatingHours(List<String> operatingHours) { this.operatingHours = operatingHours; }
    public Set<FulfilmentOption> getFulfilmentOptions() { return fulfilmentOptions; }
    public void setFulfilmentOptions(Set<FulfilmentOption> fulfilmentOptions) { this.fulfilmentOptions = fulfilmentOptions; }
    public Set<String> getDeliveryAreas() { return deliveryAreas; }
    public void setDeliveryAreas(Set<String> deliveryAreas) { this.deliveryAreas = deliveryAreas; }
}
