package com.momocircle.domain;

import com.momocircle.domain.enums.BulkAllocationStatus;
import com.momocircle.domain.enums.StockFulfilmentOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One business's share of a group: its own quantity, price lock, payable amount and (after finalisation) its OWN
 * {@link StockOrder}. Payment and fulfilment state are read from that order, never copied here.
 */
@Entity
@Table(name = "bulk_allocations",
        uniqueConstraints = @UniqueConstraint(name = "uq_bulk_alloc_group_business", columnNames = {"group_id", "business_id"}))
public class BulkAllocation extends BaseEntity {

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private BulkPurchaseGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BulkAllocationStatus status = BulkAllocationStatus.JOINED;

    @Column(nullable = false)
    private int requestedPacks;
    private Integer confirmedPacks;

    /** The most per pack this business agreed to. The final price may be lower, never higher, without re-confirmation. */
    @Column(precision = 19, scale = 2)
    private BigDecimal agreedMaxPricePerPack;
    private Instant agreedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StockFulfilmentOption fulfilmentMethod = StockFulfilmentOption.SELF_COLLECTION;
    @Column(length = 500)
    private String deliveryAddress;

    // ---- Set when the stock order is created at finalisation ----
    @Column(precision = 19, scale = 2)
    private BigDecimal finalPricePerPack;
    @Column(precision = 19, scale = 2)
    private BigDecimal discountPerPack;
    @Column(precision = 19, scale = 2)
    private BigDecimal subtotal;
    @Column(precision = 19, scale = 2)
    private BigDecimal deliveryFee;
    @Column(precision = 19, scale = 2)
    private BigDecimal total;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_order_id")
    private StockOrder stockOrder;

    @Column(length = 36)
    private String saveToBuyRequestId;

    // ---- Shortage / exceptions (recorded, never auto-refunded) ----
    private Integer allocatedPacks;
    private int shortagePacks = 0;
    private int issuePacks = 0;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal refundDueAmount = BigDecimal.ZERO;

    public long getVersion() { return version; }
    public BulkPurchaseGroup getGroup() { return group; }
    public void setGroup(BulkPurchaseGroup g) { this.group = g; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business b) { this.business = b; }
    public BulkAllocationStatus getStatus() { return status; }
    public void setStatus(BulkAllocationStatus s) { this.status = s; }
    public int getRequestedPacks() { return requestedPacks; }
    public void setRequestedPacks(int v) { this.requestedPacks = v; }
    public Integer getConfirmedPacks() { return confirmedPacks; }
    public void setConfirmedPacks(Integer v) { this.confirmedPacks = v; }
    public BigDecimal getAgreedMaxPricePerPack() { return agreedMaxPricePerPack; }
    public void setAgreedMaxPricePerPack(BigDecimal v) { this.agreedMaxPricePerPack = v; }
    public Instant getAgreedAt() { return agreedAt; }
    public void setAgreedAt(Instant v) { this.agreedAt = v; }
    public StockFulfilmentOption getFulfilmentMethod() { return fulfilmentMethod; }
    public void setFulfilmentMethod(StockFulfilmentOption v) { this.fulfilmentMethod = v; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String v) { this.deliveryAddress = v; }
    public BigDecimal getFinalPricePerPack() { return finalPricePerPack; }
    public void setFinalPricePerPack(BigDecimal v) { this.finalPricePerPack = v; }
    public BigDecimal getDiscountPerPack() { return discountPerPack; }
    public void setDiscountPerPack(BigDecimal v) { this.discountPerPack = v; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal v) { this.subtotal = v; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal v) { this.deliveryFee = v; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal v) { this.total = v; }
    public StockOrder getStockOrder() { return stockOrder; }
    public void setStockOrder(StockOrder o) { this.stockOrder = o; }
    public String getSaveToBuyRequestId() { return saveToBuyRequestId; }
    public void setSaveToBuyRequestId(String v) { this.saveToBuyRequestId = v; }
    public Integer getAllocatedPacks() { return allocatedPacks; }
    public void setAllocatedPacks(Integer v) { this.allocatedPacks = v; }
    public int getShortagePacks() { return shortagePacks; }
    public void setShortagePacks(int v) { this.shortagePacks = v; }
    public int getIssuePacks() { return issuePacks; }
    public void setIssuePacks(int v) { this.issuePacks = v; }
    public BigDecimal getRefundDueAmount() { return refundDueAmount; }
    public void setRefundDueAmount(BigDecimal v) { this.refundDueAmount = v; }
}
