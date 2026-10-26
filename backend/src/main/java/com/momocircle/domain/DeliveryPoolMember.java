package com.momocircle.domain;

import com.momocircle.domain.momoride.Driver;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A driver belonging to a business's own delivery pool (capability CAN_MANAGE_DELIVERY_FLEET). */
@Entity
@Table(name = "delivery_pool_members")
public class DeliveryPoolMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @Column(nullable = false)
    private boolean active = true;

    /** PENDING (invited) -> ACTIVE (driver accepted) -> REMOVED. active mirrors status == ACTIVE. */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; this.active = "ACTIVE".equals(status); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
