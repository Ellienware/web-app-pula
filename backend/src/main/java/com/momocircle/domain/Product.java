package com.momocircle.domain;

import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.ProductStatus;
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

/**
 * A product or service listed by a Business — in price-comparison terms,
 * this plays the "RetailerProduct" role against the shared
 * {@link CanonicalProduct} it optionally links to. Services (isService =
 * true) skip stock tracking; the architecture keeps them in the same table
 * since they share every other field and both flow through the same
 * cart/order pipeline.
 */
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @ElementCollection
    private java.util.List<String> imageUrls = new java.util.ArrayList<>();

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    /** ISO 4217. Must match the owning business's operating currency at creation time. */
    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    @Column(nullable = false)
    private boolean isService = false;

    /** Ignored (always treated as unlimited) when isService = true. */
    @Column(nullable = false)
    private int stock = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status = ProductStatus.DRAFT;

    /** Optional link into the normalized product catalogue — see CanonicalProduct's javadoc. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonical_product_id")
    private CanonicalProduct canonicalProduct;

    /** PULA Food: the menu section this dish sits under (products.menu_category_id, added in V26). Null for non-food products. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_category_id")
    private MenuCategory menuCategory;

    /**
     * Derived, not independently settable: UNIQUE_PRODUCT while
     * canonicalProduct is null, EXACT_MATCH once linked. Stored (rather
     * than computed on read) so price-comparison queries can filter on it
     * directly. See ProductService#linkCanonicalProduct.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MatchClassification matchClassification = MatchClassification.UNIQUE_PRODUCT;

    @Column(precision = 3, scale = 2)
    private BigDecimal ratingAverage = BigDecimal.ZERO;

    @Column(nullable = false)
    private int ratingCount = 0;

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public java.util.List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(java.util.List<String> imageUrls) { this.imageUrls = imageUrls; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isService() { return isService; }
    public void setService(boolean service) { isService = service; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public ProductStatus getStatus() { return status; }
    public void setStatus(ProductStatus status) { this.status = status; }
    public MenuCategory getMenuCategory() { return menuCategory; }
    public void setMenuCategory(MenuCategory menuCategory) { this.menuCategory = menuCategory; }
    public CanonicalProduct getCanonicalProduct() { return canonicalProduct; }
    public void setCanonicalProduct(CanonicalProduct canonicalProduct) { this.canonicalProduct = canonicalProduct; }
    public MatchClassification getMatchClassification() { return matchClassification; }
    public void setMatchClassification(MatchClassification matchClassification) { this.matchClassification = matchClassification; }
    public BigDecimal getRatingAverage() { return ratingAverage; }
    public void setRatingAverage(BigDecimal ratingAverage) { this.ratingAverage = ratingAverage; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
}
