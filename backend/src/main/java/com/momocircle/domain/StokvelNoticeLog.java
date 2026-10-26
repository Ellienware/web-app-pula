package com.momocircle.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "stokvel_notice_log")
public class StokvelNoticeLog extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false)
    private int periodIndex;
    @Column(nullable = false, length = 16)
    private String kind;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getPeriodIndex() { return periodIndex; }
    public void setPeriodIndex(int periodIndex) { this.periodIndex = periodIndex; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}
