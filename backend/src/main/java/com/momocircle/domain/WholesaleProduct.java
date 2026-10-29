package com.momocircle.domain;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Column;
import com.momocircle.domain.enums.MatchClassification;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A product in a supplier's B2B wholesale catalogue (brief §12-13). Distinct from
 * {@link Product} (the consumer catalogue) because wholesale products have
 * pack-size, MOQ, and available-stock semantics that don't apply to consumer
 * listings — and mixing them would break price-comparison logic (which operates
 * on consumer products only). A supplier may list the same item in both catalogues
 * at different prices if they sell both retail and wholesale.
 *
 * Only accessible to businesses with CAN_SUPPLY_BUSINESSES (as the supplier) and
 * CAN_BUY_STOCK (as the buyer). Never exposed to unauthenticated users.
 */
@Entity
@Table(name = "wholesale_products")
public class WholesaleProduct extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_business_id", nullable = false)
    private Business supplierBusiness;

    /** Optional link to the shared canonical catalogue; never auto-assigned. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonical_product_id")
    private CanonicalProduct canonicalProduct;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_classification", nullable = false, length = 32)
    private MatchClassification matchClassification = MatchClassification.UNIQUE_PRODUCT;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    /** Up to four product images, stored as URLs or uploaded-image paths. */
    @jakarta.persistence.ElementCollection
    @jakarta.persistence.CollectionTable(
            name = "wholesale_product_image_urls",
            joinColumns = @jakarta.persistence.JoinColumn(name = "wholesale_product_id")
    )
    @jakarta.persistence.Column(name = "image_url", nullable = false, length = 500)
    private java.util.List<String> imageUrls = new java.util.ArrayList<>();

    /** Optional SKU — supplier's own reference code for this product. */
    @Column(length = 100)
    private String sku;

    /** Product category for browsing (e.g. "Beverages", "Cleaning", "Snacks"). */
    @Column(length = 100)
    private String category;

    /** How many units are in one purchasable pack (e.g. 24 cans per case). */
    @Column(nullable = false)
    private int packSize = 1;

    /** Minimum order quantity in packs — buyers must order at least this many packs. */
    @Column(nullable = false)
    private int moq = 1;

    /** Supported purchase modes: INDIVIDUAL_ONLY, BULK_ONLY, or BOTH. */
    @Column(name = "selling_mode", nullable = false, length = 30)
    private String sellingMode = "BULK_ONLY";

    /** Explicit individual-unit price; never inferred from the pack price. */
    @Column(name = "price_per_unit", precision = 19, scale = 2)
    private BigDecimal pricePerUnit;

    /** Wholesale price per pack (not per individual unit). Backend is authoritative — never trust client price. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerPack;

    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    /** Number of packs currently available. Decremented on stock order creation. */
    @Column(nullable = false)
    private int availableStock = 0;

    @Column(nullable = false)
    private boolean active = true;

    public Business getSupplierBusiness() { return supplierBusiness; }
    public void setSupplierBusiness(Business supplierBusiness) { this.supplierBusiness = supplierBusiness; }
    public CanonicalProduct getCanonicalProduct() { return canonicalProduct; }
    public void setCanonicalProduct(CanonicalProduct canonicalProduct) { this.canonicalProduct = canonicalProduct; }
    public MatchClassification getMatchClassification() { return matchClassification; }
    public void setMatchClassification(MatchClassification matchClassification) {
        this.matchClassification = matchClassification == null
                ? MatchClassification.UNIQUE_PRODUCT : matchClassification;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public java.util.List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(java.util.List<String> imageUrls) {
        this.imageUrls = imageUrls == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(imageUrls);
    }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getPackSize() { return packSize; }
    public void setPackSize(int packSize) { this.packSize = packSize; }
    public int getMoq() { return moq; }
    public void setMoq(int moq) { this.moq = moq; }
    public String getSellingMode() { return sellingMode; }
    public void setSellingMode(String sellingMode) { this.sellingMode = sellingMode; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public void setPricePerUnit(BigDecimal pricePerUnit) { this.pricePerUnit = pricePerUnit; }
    public BigDecimal getPricePerPack() { return pricePerPack; }
    public void setPricePerPack(BigDecimal pricePerPack) { this.pricePerPack = pricePerPack; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getAvailableStock() { return availableStock; }
    public void setAvailableStock(int availableStock) { this.availableStock = availableStock; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
