package com.momocircle.domain;

import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A purchasing opportunity: several independent businesses combine demand for ONE wholesale product from ONE supplier.
 * It is a coordination record only. It is not a legal entity, holds no money and has no wallet; every business pays for its
 * own {@link BulkAllocation} through the existing stock checkout.
 */
@Entity
@Table(name = "bulk_purchase_groups")
public class BulkPurchaseGroup extends BaseEntity {

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_business_id", nullable = false)
    private Business creatorBusiness;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_business_id", nullable = false)
    private Business supplierBusiness;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct wholesaleProduct;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    /** Shared procurement reference that ties the parent group to every business-level order. Unique. */
    @Column(nullable = false, unique = true, length = 40)
    private String procurementReference;

    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    /** Product price when the group was created; the group's terms never silently follow later supplier edits. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal basePricePerPack;

    @Column(nullable = false)
    private int packSizeSnapshot = 1;

    @Column(nullable = false)
    private int moqPacks = 1;

    @Column(nullable = false)
    private int targetPacks;

    @Column(nullable = false)
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BulkShortfallPolicy shortfallPolicy = BulkShortfallPolicy.CANCEL;

    @Column(nullable = false)
    private int extensionHours = 48;

    @Column(nullable = false)
    private int extensionCount = 0;

    @Column(nullable = false)
    private int confirmationWindowsUsed = 0;

    @Column(nullable = false)
    private boolean allowSelfCollection = true;
    @Column(nullable = false)
    private boolean allowSupplierDelivery = false;
    @Column(nullable = false)
    private boolean allowPulaRide = false;

    @Column(length = 500)
    private String collectionNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BulkGroupStatus status = BulkGroupStatus.DRAFT;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bulk_group_tiers", joinColumns = @JoinColumn(name = "group_id"))
    private List<BulkTier> tiers = new ArrayList<>();

    // ---- Set at finalisation / supplier confirmation ----
    @Column(precision = 19, scale = 2)
    private BigDecimal finalPricePerPack;
    private Integer finalAggregatePacks;
    private Instant finalisedAt;
    private Instant orderPlacedAt;
    private Integer supplierConfirmedPacks;
    @Column(length = 100)
    private String supplierReference;
    @Column(length = 500)
    private String supplierNote;
    @Column(length = 500)
    private String cancelReason;

    public long getVersion() { return version; }
    public Business getCreatorBusiness() { return creatorBusiness; }
    public void setCreatorBusiness(Business b) { this.creatorBusiness = b; }
    public Business getSupplierBusiness() { return supplierBusiness; }
    public void setSupplierBusiness(Business b) { this.supplierBusiness = b; }
    public WholesaleProduct getWholesaleProduct() { return wholesaleProduct; }
    public void setWholesaleProduct(WholesaleProduct p) { this.wholesaleProduct = p; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProcurementReference() { return procurementReference; }
    public void setProcurementReference(String r) { this.procurementReference = r; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getBasePricePerPack() { return basePricePerPack; }
    public void setBasePricePerPack(BigDecimal p) { this.basePricePerPack = p; }
    public int getPackSizeSnapshot() { return packSizeSnapshot; }
    public void setPackSizeSnapshot(int v) { this.packSizeSnapshot = v; }
    public int getMoqPacks() { return moqPacks; }
    public void setMoqPacks(int v) { this.moqPacks = v; }
    public int getTargetPacks() { return targetPacks; }
    public void setTargetPacks(int v) { this.targetPacks = v; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant d) { this.deadline = d; }
    public BulkShortfallPolicy getShortfallPolicy() { return shortfallPolicy; }
    public void setShortfallPolicy(BulkShortfallPolicy p) { this.shortfallPolicy = p; }
    public int getExtensionHours() { return extensionHours; }
    public void setExtensionHours(int v) { this.extensionHours = v; }
    public int getExtensionCount() { return extensionCount; }
    public void setExtensionCount(int v) { this.extensionCount = v; }
    public int getConfirmationWindowsUsed() { return confirmationWindowsUsed; }
    public void setConfirmationWindowsUsed(int v) { this.confirmationWindowsUsed = v; }
    public boolean isAllowSelfCollection() { return allowSelfCollection; }
    public void setAllowSelfCollection(boolean v) { this.allowSelfCollection = v; }
    public boolean isAllowSupplierDelivery() { return allowSupplierDelivery; }
    public void setAllowSupplierDelivery(boolean v) { this.allowSupplierDelivery = v; }
    public boolean isAllowPulaRide() { return allowPulaRide; }
    public void setAllowPulaRide(boolean v) { this.allowPulaRide = v; }
    public String getCollectionNote() { return collectionNote; }
    public void setCollectionNote(String v) { this.collectionNote = v; }
    public BulkGroupStatus getStatus() { return status; }
    public void setStatus(BulkGroupStatus s) { this.status = s; }
    public List<BulkTier> getTiers() { return tiers; }
    public BigDecimal getFinalPricePerPack() { return finalPricePerPack; }
    public void setFinalPricePerPack(BigDecimal v) { this.finalPricePerPack = v; }
    public Integer getFinalAggregatePacks() { return finalAggregatePacks; }
    public void setFinalAggregatePacks(Integer v) { this.finalAggregatePacks = v; }
    public Instant getFinalisedAt() { return finalisedAt; }
    public void setFinalisedAt(Instant v) { this.finalisedAt = v; }
    public Instant getOrderPlacedAt() { return orderPlacedAt; }
    public void setOrderPlacedAt(Instant v) { this.orderPlacedAt = v; }
    public Integer getSupplierConfirmedPacks() { return supplierConfirmedPacks; }
    public void setSupplierConfirmedPacks(Integer v) { this.supplierConfirmedPacks = v; }
    public String getSupplierReference() { return supplierReference; }
    public void setSupplierReference(String v) { this.supplierReference = v; }
    public String getSupplierNote() { return supplierNote; }
    public void setSupplierNote(String v) { this.supplierNote = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { this.cancelReason = v; }
}
