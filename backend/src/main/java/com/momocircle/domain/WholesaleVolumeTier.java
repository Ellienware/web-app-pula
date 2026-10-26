package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** A supplier-configured volume price for one wholesale product: from {@code minPacks} packs the price per pack is lower. */
@Entity
@Table(name = "wholesale_volume_tiers")
public class WholesaleVolumeTier extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct wholesaleProduct;

    @Column(nullable = false)
    private int minPacks;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerPack;

    public WholesaleProduct getWholesaleProduct() { return wholesaleProduct; }
    public void setWholesaleProduct(WholesaleProduct wholesaleProduct) { this.wholesaleProduct = wholesaleProduct; }
    public int getMinPacks() { return minPacks; }
    public void setMinPacks(int minPacks) { this.minPacks = minPacks; }
    public BigDecimal getPricePerPack() { return pricePerPack; }
    public void setPricePerPack(BigDecimal pricePerPack) { this.pricePerPack = pricePerPack; }
}
