package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.User;
import jakarta.persistence.*;

@Entity @Table(name="momoride_drivers", uniqueConstraints=@UniqueConstraint(name="uq_momoride_driver_user", columnNames="user_id"))
public class Driver extends BaseEntity {
    @OneToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id", nullable=false, unique=true)
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24)
    private DriverStatus status = DriverStatus.ACTIVE;
    /** BCrypt hash of the driver's USSD PIN. Never plaintext, never logged, never returned by any API. */
    @Column(name="pin_hash", length=100)
    private String pinHash;
    public User getUser(){return user;} public void setUser(User user){this.user=user;}
    public DriverStatus getStatus(){return status;} public void setStatus(DriverStatus status){this.status=status;}
    public String getPinHash(){return pinHash;} public void setPinHash(String pinHash){this.pinHash=pinHash;}
    public boolean hasPin(){return pinHash!=null && !pinHash.isBlank();}
    public enum DriverStatus { ACTIVE, SUSPENDED, INACTIVE }
}
