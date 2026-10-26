package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_provider_profiles")
public class ServiceProviderProfile extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false)
    private Business business;
    @Column(length = 160)
    private String headline;
    @Column(length = 2000)
    private String about;
    @Column(length = 160)
    private String contactEmail;
    @Column(length = 300)
    private String availabilityNote;
    @Column(nullable = false)
    private boolean acceptingRequests = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ProviderVerificationStatus verificationStatus = ProviderVerificationStatus.UNVERIFIED;
    private Instant verifiedAt;
    @Column(nullable = false)
    private boolean suspended = false;
    @Column(length = 300)
    private String suspendedReason;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_provider_areas", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "area", nullable = false, length = 80)
    private List<String> serviceAreas = new ArrayList<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_provider_portfolio", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "image_url", nullable = false, length = 500)
    private List<String> portfolioUrls = new ArrayList<>();

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getAbout() { return about; }
    public void setAbout(String about) { this.about = about; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getAvailabilityNote() { return availabilityNote; }
    public void setAvailabilityNote(String availabilityNote) { this.availabilityNote = availabilityNote; }
    public boolean isAcceptingRequests() { return acceptingRequests; }
    public void setAcceptingRequests(boolean acceptingRequests) { this.acceptingRequests = acceptingRequests; }
    public ProviderVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(ProviderVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public String getSuspendedReason() { return suspendedReason; }
    public void setSuspendedReason(String suspendedReason) { this.suspendedReason = suspendedReason; }
    public List<String> getServiceAreas() { return serviceAreas; }
    public void setServiceAreas(List<String> serviceAreas) { this.serviceAreas = serviceAreas; }
    public List<String> getPortfolioUrls() { return portfolioUrls; }
    public void setPortfolioUrls(List<String> portfolioUrls) { this.portfolioUrls = portfolioUrls; }
}
