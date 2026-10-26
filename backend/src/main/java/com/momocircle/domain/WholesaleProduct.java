package com.momocircle.domain;

import jakarta.persistence.Column;
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

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getPackSize() { return packSize; }
    public void setPackSize(int packSize) { this.packSize = packSize; }
    public int getMoq() { return moq; }
    public void setMoq(int moq) { this.moq = moq; }
    public BigDecimal getPricePerPack() { return pricePerPack; }
    public void setPricePerPack(BigDecimal pricePerPack) { this.pricePerPack = pricePerPack; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getAvailableStock() { return availableStock; }
    public void setAvailableStock(int availableStock) { this.availableStock = availableStock; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
