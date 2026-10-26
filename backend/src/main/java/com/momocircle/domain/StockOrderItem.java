package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One line in a {@link StockOrder} — price and product details are frozen at
 * order time so later catalogue changes (supplier updates the wholesale price,
 * renames a product, etc.) never silently alter a buyer's historical records.
 * Mirrors the OrderItem "snapshot" pattern used by consumer orders.
 */
@Entity
@Table(name = "stock_order_items")
public class StockOrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_order_id", nullable = false)
    private StockOrder stockOrder;

    /** Kept for traceability, but snapshot fields below are what matters. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct wholesaleProduct;

    @Column(nullable = false)
    private String productNameSnapshot;

    @Column(length = 100)
    private String skuSnapshot;

    /** Pack size at time of order — determines how many units the buyer receives. */
    @Column(nullable = false)
    private int packSizeSnapshot;

    @Column(nullable = false)
    private int quantityPacks;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerPackSnapshot;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;

    public StockOrder getStockOrder() { return stockOrder; }
    public void setStockOrder(StockOrder stockOrder) { this.stockOrder = stockOrder; }
    public WholesaleProduct getWholesaleProduct() { return wholesaleProduct; }
    public void setWholesaleProduct(WholesaleProduct wholesaleProduct) { this.wholesaleProduct = wholesaleProduct; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String productNameSnapshot) { this.productNameSnapshot = productNameSnapshot; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public void setSkuSnapshot(String skuSnapshot) { this.skuSnapshot = skuSnapshot; }
    public int getPackSizeSnapshot() { return packSizeSnapshot; }
    public void setPackSizeSnapshot(int packSizeSnapshot) { this.packSizeSnapshot = packSizeSnapshot; }
    public int getQuantityPacks() { return quantityPacks; }
    public void setQuantityPacks(int quantityPacks) { this.quantityPacks = quantityPacks; }
    public BigDecimal getPricePerPackSnapshot() { return pricePerPackSnapshot; }
    public void setPricePerPackSnapshot(BigDecimal pricePerPackSnapshot) { this.pricePerPackSnapshot = pricePerPackSnapshot; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}
