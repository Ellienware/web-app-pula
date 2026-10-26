package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** PULA Food: a group of optional choices (Sides, Extras). Required when minSelect >= 1. */
@Entity
@Table(name = "modifier_groups")
public class ModifierGroup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int minSelect = 0;

    @Column(nullable = false)
    private int maxSelect = 1;

    public boolean isRequired() { return minSelect >= 1; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getMinSelect() { return minSelect; }
    public void setMinSelect(int minSelect) { this.minSelect = minSelect; }
    public int getMaxSelect() { return maxSelect; }
    public void setMaxSelect(int maxSelect) { this.maxSelect = maxSelect; }
}
