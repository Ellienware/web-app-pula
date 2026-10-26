package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="momoride_payment_requests")
public class TaxiPaymentRequest extends BaseEntity {
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="taxi_id", nullable=false) private Taxi taxi;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="driver_id", nullable=false) private Driver driver;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="passenger_id") private User passenger;
 @Column(nullable=false, precision=19, scale=2) private BigDecimal amount;
 @Column(nullable=false,length=3) private String currency;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status=Status.OPEN;
 @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="payment_id", unique=true) private Payment payment;
 @Column(name="expires_at") private Instant expiresAt;
 public Taxi getTaxi(){return taxi;} public void setTaxi(Taxi v){taxi=v;} public Driver getDriver(){return driver;} public void setDriver(Driver v){driver=v;}
 public User getPassenger(){return passenger;} public void setPassenger(User v){passenger=v;} public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
 public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public Status getStatus(){return status;} public void setStatus(Status v){status=v;}
 public Payment getPayment(){return payment;} public void setPayment(Payment v){payment=v;}
 public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
 public enum Status { OPEN, PROCESSING, FULFILLED, CANCELLED, EXPIRED, FAILED }
}
