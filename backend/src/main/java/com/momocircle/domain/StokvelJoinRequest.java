package com.momocircle.domain;

import com.momocircle.domain.enums.JoinRequestSource;
import com.momocircle.domain.enums.JoinRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A person's request to join a stokvel. Final states are written only through the guarded updates in
 * {@code StokvelJoinRequestRepository}; {@code pendingKey} is non-null exactly while PENDING (see V45).
 */
@Entity
@Table(name = "stokvel_join_requests")
public class StokvelJoinRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false, updatable = false)
    private Circle circle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JoinRequestStatus status = JoinRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private JoinRequestSource source;

    @Column(length = 300, updatable = false)
    private String message;

    @Column(name = "pending_key", length = 80)
    private String pendingKey;

    @Column(name = "invite_link_id", length = 36, updatable = false)
    private String inviteLinkId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    private Instant decidedAt;

    @Column(length = 300)
    private String decisionNote;

    public static String pendingKeyFor(String circleId, String userId) { return circleId + ":" + userId; }

    public static StokvelJoinRequest open(Circle circle, User user, JoinRequestSource source, String message, String inviteLinkId) {
        StokvelJoinRequest r = new StokvelJoinRequest();
        r.circle = circle;
        r.user = user;
        r.source = source;
        r.message = message;
        r.inviteLinkId = inviteLinkId;
        r.status = JoinRequestStatus.PENDING;
        r.pendingKey = pendingKeyFor(circle.getId(), user.getId());
        return r;
    }

    public Circle getCircle() { return circle; }
    public User getUser() { return user; }
    public JoinRequestStatus getStatus() { return status; }
    public JoinRequestSource getSource() { return source; }
    public String getMessage() { return message; }
    public String getPendingKey() { return pendingKey; }
    public String getInviteLinkId() { return inviteLinkId; }
    public User getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
