package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A shareable invite code/link. Only the SHA-256 of the code is stored (shown once, at creation). Use is counted by a guarded
 * UPDATE (see the repository) so concurrent redemptions can never exceed {@code maxUses}.
 */
@Entity
@Table(name = "stokvel_invite_links")
public class StokvelInviteLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false, updatable = false)
    private Circle circle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "code_hash", length = 64, nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "code_hint", length = 8, nullable = false, updatable = false)
    private String codeHint;

    @Column(name = "requires_approval", nullable = false, updatable = false)
    private boolean requiresApproval;

    @Column(name = "max_uses", nullable = false, updatable = false)
    private int maxUses;

    @Column(name = "uses_count", nullable = false)
    private int usesCount;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_id")
    private User revokedBy;

    public static StokvelInviteLink create(Circle circle, User createdBy, String codeHash, String codeHint, boolean requiresApproval,
                                           int maxUses, Instant expiresAt) {
        StokvelInviteLink l = new StokvelInviteLink();
        l.circle = circle;
        l.createdBy = createdBy;
        l.codeHash = codeHash;
        l.codeHint = codeHint;
        l.requiresApproval = requiresApproval;
        l.maxUses = maxUses;
        l.usesCount = 0;
        l.expiresAt = expiresAt;
        return l;
    }

    public Circle getCircle() { return circle; }
    public User getCreatedBy() { return createdBy; }
    public String getCodeHash() { return codeHash; }
    public String getCodeHint() { return codeHint; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public int getMaxUses() { return maxUses; }
    public int getUsesCount() { return usesCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public User getRevokedBy() { return revokedBy; }
}
