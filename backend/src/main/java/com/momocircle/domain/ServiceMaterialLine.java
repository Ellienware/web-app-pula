package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_material_lines")
public class ServiceMaterialLine extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "request_id", nullable = false)
    private ServiceRequest request;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct wholesaleProduct;
    @Column(nullable = false)
    private int quantityPacks;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private MaterialSupplier suppliedBy;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal unitPriceSnapshot;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "stock_order_id")
    private StockOrder stockOrder;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "added_by_id", nullable = false)
    private User addedBy;

    public ServiceRequest getRequest() { return request; }
    public void setRequest(ServiceRequest request) { this.request = request; }
    public WholesaleProduct getWholesaleProduct() { return wholesaleProduct; }
    public void setWholesaleProduct(WholesaleProduct wholesaleProduct) { this.wholesaleProduct = wholesaleProduct; }
    public int getQuantityPacks() { return quantityPacks; }
    public void setQuantityPacks(int quantityPacks) { this.quantityPacks = quantityPacks; }
    public MaterialSupplier getSuppliedBy() { return suppliedBy; }
    public void setSuppliedBy(MaterialSupplier suppliedBy) { this.suppliedBy = suppliedBy; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public void setUnitPriceSnapshot(BigDecimal unitPriceSnapshot) { this.unitPriceSnapshot = unitPriceSnapshot; }
    public StockOrder getStockOrder() { return stockOrder; }
    public void setStockOrder(StockOrder stockOrder) { this.stockOrder = stockOrder; }
    public User getAddedBy() { return addedBy; }
    public void setAddedBy(User addedBy) { this.addedBy = addedBy; }
}
