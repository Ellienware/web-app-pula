package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * PULA Food: the structured meaning of a canonical food (a CanonicalProduct with category MEAL).
 * Menu items are compared to it by attribute, never by name alone. Every field is optional;
 * an unknown field is treated as unknown (reported as uncertainty), never as a match.
 */
@Entity
@Table(name = "canonical_food_attributes")
public class CanonicalFoodAttributes extends BaseEntity {

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "canonical_product_id", nullable = false, unique = true)
    private CanonicalProduct canonicalProduct;

    private String foodType;
    private String protein;
    private String starch;
    private String portionClass;

    /** Sorted, comma-joined side tokens; "" when the canonical meal has no standard sides. */
    @Column(nullable = false)
    private String sidesSignature = "";

    public CanonicalProduct getCanonicalProduct() { return canonicalProduct; }
    public void setCanonicalProduct(CanonicalProduct canonicalProduct) { this.canonicalProduct = canonicalProduct; }
    public String getFoodType() { return foodType; }
    public void setFoodType(String foodType) { this.foodType = foodType; }
    public String getProtein() { return protein; }
    public void setProtein(String protein) { this.protein = protein; }
    public String getStarch() { return starch; }
    public void setStarch(String starch) { this.starch = starch; }
    public String getPortionClass() { return portionClass; }
    public void setPortionClass(String portionClass) { this.portionClass = portionClass; }
    public String getSidesSignature() { return sidesSignature; }
    public void setSidesSignature(String sidesSignature) { this.sidesSignature = sidesSignature; }
}
