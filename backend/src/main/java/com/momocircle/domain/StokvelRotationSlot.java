package com.momocircle.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "stokvel_rotation_slots")
public class StokvelRotationSlot extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "slot_position", nullable = false)
    private int position;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
