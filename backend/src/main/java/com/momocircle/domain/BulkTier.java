package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** Snapshot of a supplier tier taken when the group is created, so later supplier edits cannot silently change a group's terms. */
@Embeddable
public class BulkTier {
    @Column(nullable = false)
    private int minPacks;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerPack;

    protected BulkTier() {}
    public BulkTier(int minPacks, BigDecimal pricePerPack) { this.minPacks = minPacks; this.pricePerPack = pricePerPack; }
    public int getMinPacks() { return minPacks; }
    public BigDecimal getPricePerPack() { return pricePerPack; }
}
