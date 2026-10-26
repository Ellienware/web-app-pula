package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * PULA Food: per-branch override of a shared menu item. No row means "available at the
 * product's own price". Unique per (branch, product).
 */
@Entity
@Table(name = "branch_item_availability")
public class BranchItemAvailability extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private BusinessBranch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private boolean available = true;

    @Column(precision = 19, scale = 2)
    private BigDecimal priceOverride;

    public BusinessBranch getBranch() { return branch; }
    public void setBranch(BusinessBranch branch) { this.branch = branch; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public BigDecimal getPriceOverride() { return priceOverride; }
    public void setPriceOverride(BigDecimal priceOverride) { this.priceOverride = priceOverride; }
}
