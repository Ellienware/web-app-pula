package com.momocircle.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Opaque public id (BW-...) for a business wallet. Holds no balance and no personal data. Maps {@code business_wallet_profiles} (V43). */
@Entity
@Table(name = "business_wallet_profiles")
public class BusinessWalletProfileEntity {

    @Id
    @Column(name = "business_id", length = 36, nullable = false, updatable = false)
    private String businessId;

    @Column(name = "public_id", length = 16, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BusinessWalletProfileEntity() {
        // JPA
    }

    public BusinessWalletProfileEntity(String businessId, String publicId, Instant createdAt) {
        this.businessId = businessId;
        this.publicId = publicId;
        this.createdAt = createdAt;
    }

    public String getBusinessId() { return businessId; }
    public String getPublicId() { return publicId; }
    public Instant getCreatedAt() { return createdAt; }
}
