package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import jakarta.persistence.*;

@Entity @Table(name="momoride_ride_bookings", uniqueConstraints=@UniqueConstraint(name="uq_momoride_booking_ride_user",columnNames={"ride_id","passenger_id"}))
public class RideBooking extends BaseEntity {
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ride_id",nullable=false) private Ride ride;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="passenger_id",nullable=false) private User passenger;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status=Status.CONFIRMED;
 @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="payment_id",unique=true) private Payment payment;
 public Ride getRide(){return ride;} public void setRide(Ride v){ride=v;} public User getPassenger(){return passenger;} public void setPassenger(User v){passenger=v;}
 public Status getStatus(){return status;} public void setStatus(Status v){status=v;} public Payment getPayment(){return payment;} public void setPayment(Payment v){payment=v;}
 public enum Status { CONFIRMED, CANCELLED }
}
