package com.momocircle.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "stokvel_contribution_links")
public class StokvelContributionLink extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "contribution_id", nullable = false, unique = true)
    private Contribution contribution;
    @Column(nullable = false)
    private int periodIndex;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Contribution getContribution() { return contribution; }
    public void setContribution(Contribution contribution) { this.contribution = contribution; }
    public int getPeriodIndex() { return periodIndex; }
    public void setPeriodIndex(int periodIndex) { this.periodIndex = periodIndex; }
}
