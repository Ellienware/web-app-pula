package com.momocircle.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** PULA Food: attaches a ModifierGroup to a menu item. Unique per (product, group). */
@Entity
@Table(name = "product_modifier_groups")
public class ProductModifierGroup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ModifierGroup group;

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public ModifierGroup getGroup() { return group; }
    public void setGroup(ModifierGroup group) { this.group = group; }
}
