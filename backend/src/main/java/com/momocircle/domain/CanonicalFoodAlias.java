package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** PULA Food: another way people write a canonical food ("beef and pap"). Stored already normalised. */
@Entity
@Table(name = "canonical_food_aliases")
public class CanonicalFoodAlias extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "canonical_product_id", nullable = false)
    private CanonicalProduct canonicalProduct;

    @Column(nullable = false, length = 160)
    private String alias;

    public CanonicalProduct getCanonicalProduct() { return canonicalProduct; }
    public void setCanonicalProduct(CanonicalProduct canonicalProduct) { this.canonicalProduct = canonicalProduct; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
}
