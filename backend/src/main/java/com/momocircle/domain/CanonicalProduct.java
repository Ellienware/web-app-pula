package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A normalized product description ("Full Cream Milk 2L") shared across
 * sellers, distinct from {@link Product} (which stays the per-seller
 * listing — its price, stock, images, and status). A Product optionally
 * links to one CanonicalProduct via {@code Product.canonicalProduct}; the
 * price-comparison engine groups by that link, not by name-string
 * similarity, so it never has to guess whether two differently-worded
 * listings are actually the same item.
 *
 * Anyone with a Business can create a CanonicalProduct (there's no separate
 * curator role in this slice) — the search-before-create flow in
 * CanonicalProductService is the only de-duplication mechanism today. A
 * dedicated moderation/merge workflow is a reasonable follow-up once real
 * usage shows how much duplication actually happens in practice, not
 * something to build ahead of evidence it's needed.
 */
@Entity
@Table(name = "canonical_products")
public class CanonicalProduct extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String brand;

    @Column(nullable = false)
    private String category;

    private String subcategory;

    /** Free-text size, e.g. "2", paired with unit below. Null for items without a fixed size. */
    private String size;

    /** Free-text unit, e.g. "L", "kg", "each". */
    private String unit;

    /** GTIN/barcode where known. Unique when present; many entries won't have one yet. */
    @Column(unique = true)
    private String barcode;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSubcategory() { return subcategory; }
    public void setSubcategory(String subcategory) { this.subcategory = subcategory; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
}
