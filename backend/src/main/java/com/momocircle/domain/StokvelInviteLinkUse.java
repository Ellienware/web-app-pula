package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Records that a person redeemed a link (UNIQUE per link and person): no one can use the same link twice. */
@Entity
@Table(name = "stokvel_invite_link_uses")
public class StokvelInviteLinkUse extends BaseEntity {

    @Column(name = "link_id", length = 36, nullable = false, updatable = false)
    private String linkId;

    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Column(length = 16, nullable = false, updatable = false)
    private String outcome;

    public static StokvelInviteLinkUse of(String linkId, String userId, String outcome) {
        StokvelInviteLinkUse u = new StokvelInviteLinkUse();
        u.linkId = linkId;
        u.userId = userId;
        u.outcome = outcome;
        return u;
    }

    public String getLinkId() { return linkId; }
    public String getUserId() { return userId; }
    public String getOutcome() { return outcome; }
}
