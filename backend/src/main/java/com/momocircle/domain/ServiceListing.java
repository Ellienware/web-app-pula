package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_listings")
public class ServiceListing extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false)
    private Business business;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false)
    private ServiceCategory category;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 2000)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ServicePricingModel pricingModel;
    @Column(precision = 19, scale = 2)
    private BigDecimal price;
    @Column(nullable = false, length = 3)
    private String currency;
    private Integer durationMinutes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private MaterialsResponsibility materialsResponsibility = MaterialsResponsibility.NONE;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ServiceListingStatus status = ServiceListingStatus.DRAFT;
    @Column(nullable = false)
    private boolean hiddenByAdmin = false;
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal ratingAverage = BigDecimal.ZERO;
    @Column(nullable = false)
    private int ratingCount = 0;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_listing_areas", joinColumns = @JoinColumn(name = "listing_id"))
    @Column(name = "area", nullable = false, length = 80)
    private List<String> serviceAreas = new ArrayList<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_listing_images", joinColumns = @JoinColumn(name = "listing_id"))
    @Column(name = "image_url", nullable = false, length = 500)
    private List<String> imageUrls = new ArrayList<>();

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public ServiceCategory getCategory() { return category; }
    public void setCategory(ServiceCategory category) { this.category = category; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ServicePricingModel getPricingModel() { return pricingModel; }
    public void setPricingModel(ServicePricingModel pricingModel) { this.pricingModel = pricingModel; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public MaterialsResponsibility getMaterialsResponsibility() { return materialsResponsibility; }
    public void setMaterialsResponsibility(MaterialsResponsibility materialsResponsibility) { this.materialsResponsibility = materialsResponsibility; }
    public ServiceListingStatus getStatus() { return status; }
    public void setStatus(ServiceListingStatus status) { this.status = status; }
    public boolean isHiddenByAdmin() { return hiddenByAdmin; }
    public void setHiddenByAdmin(boolean hiddenByAdmin) { this.hiddenByAdmin = hiddenByAdmin; }
    public BigDecimal getRatingAverage() { return ratingAverage; }
    public void setRatingAverage(BigDecimal ratingAverage) { this.ratingAverage = ratingAverage; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
    public List<String> getServiceAreas() { return serviceAreas; }
    public void setServiceAreas(List<String> serviceAreas) { this.serviceAreas = serviceAreas; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
}
