package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One line in a buyer business's active stock cart (brief §14). Mirrors the
 * {@link CartItem} design ("no separate Cart entity — the cart is simply these
 * rows") but scoped to a {@link Business} (the buyer) rather than a {@link User},
 * because it's the business buying stock, not an individual user.
 *
 * Uniqueness: one row per (buyer_business_id, wholesale_product_id) — adding
 * the same product a second time updates the quantity, not creates a new row.
 */
@Entity
@Table(name = "stock_cart_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"buyer_business_id", "wholesale_product_id"}))
public class StockCartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_business_id", nullable = false)
    private Business buyerBusiness;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct wholesaleProduct;

    /** Quantity in packs (not individual units). Must be >= wholesaleProduct.moq at checkout. */
    @Column(nullable = false)
    private int quantityPacks;

    public Business getBuyerBusiness() { return buyerBusiness; }
    public void setBuyerBusiness(Business buyerBusiness) { this.buyerBusiness = buyerBusiness; }
    public WholesaleProduct getWholesaleProduct() { return wholesaleProduct; }
    public void setWholesaleProduct(WholesaleProduct wholesaleProduct) { this.wholesaleProduct = wholesaleProduct; }
    public int getQuantityPacks() { return quantityPacks; }
    public void setQuantityPacks(int quantityPacks) { this.quantityPacks = quantityPacks; }
}
