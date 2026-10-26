package com.momocircle.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A user's delegated access (FINANCE or STAFF) to one business wallet. Maps {@code business_wallet_members} (V43). */
@Entity
@Table(name = "business_wallet_members")
public class BusinessWalletMemberEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "business_id", length = 36, nullable = false, updatable = false)
    private String businessId;

    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private BusinessWalletRole role;

    @Column(name = "added_by_user_id", length = 36, nullable = false, updatable = false)
    private String addedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessWalletMemberEntity() {
        // JPA
    }

    public BusinessWalletMemberEntity(String id, String businessId, String userId, BusinessWalletRole role, String addedByUserId, Instant now) {
        if (role == null || !role.isDelegable()) throw new IllegalArgumentException("Only FINANCE or STAFF can be granted");
        this.id = id;
        this.businessId = businessId;
        this.userId = userId;
        this.role = role;
        this.addedByUserId = addedByUserId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeRole(BusinessWalletRole newRole, Instant now) {
        if (newRole == null || !newRole.isDelegable()) throw new IllegalArgumentException("Only FINANCE or STAFF can be granted");
        this.role = newRole;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getBusinessId() { return businessId; }
    public String getUserId() { return userId; }
    public BusinessWalletRole getRole() { return role; }
    public String getAddedByUserId() { return addedByUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
