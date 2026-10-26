package com.momocircle.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Opaque public identifier for a user's PULA Money wallet. Holds no balance and no personal data. Maps {@code wallet_profiles} (V42). */
@Entity
@Table(name = "wallet_profiles")
public class WalletProfileEntity {

    @Id
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Column(name = "public_id", length = 16, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletProfileEntity() {
        // JPA
    }

    public WalletProfileEntity(String userId, String publicId, Instant createdAt) {
        this.userId = userId;
        this.publicId = publicId;
        this.createdAt = createdAt;
    }

    public String getUserId() { return userId; }
    public String getPublicId() { return publicId; }
    public Instant getCreatedAt() { return createdAt; }
}
