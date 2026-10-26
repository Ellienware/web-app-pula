package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import jakarta.persistence.*;

/** A passenger or driver report against a driver, ride, payment, or general safety concern. */
@Entity @Table(name="momoride_reports")
public class MoMoRideReport extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="reporter_id", nullable=false) private User reporter;
    @Enumerated(EnumType.STRING) @Column(name="target_type", nullable=false, length=24) private TargetType targetType;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="ride_id") private Ride ride;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="taxi_id") private Taxi taxi;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="payment_id") private Payment payment;
    /** Who is being reported — a driver's or passenger's User id. Never resolved/displayed beyond
        what an admin needs; no additional personal data is exposed through this entity. */
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="reported_user_id") private User reportedUser;
    @Column(nullable=false, length=1000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private Status status = Status.OPEN;

    public User getReporter(){return reporter;} public void setReporter(User v){reporter=v;}
    public TargetType getTargetType(){return targetType;} public void setTargetType(TargetType v){targetType=v;}
    public Ride getRide(){return ride;} public void setRide(Ride v){ride=v;}
    public Taxi getTaxi(){return taxi;} public void setTaxi(Taxi v){taxi=v;}
    public Payment getPayment(){return payment;} public void setPayment(Payment v){payment=v;}
    public User getReportedUser(){return reportedUser;} public void setReportedUser(User v){reportedUser=v;}
    public String getReason(){return reason;} public void setReason(String v){reason=v;}
    public Status getStatus(){return status;} public void setStatus(Status v){status=v;}

    public enum TargetType { DRIVER, PASSENGER, RIDE, PAYMENT, SAFETY }
    public enum Status { OPEN, REVIEWED, DISMISSED }
}
