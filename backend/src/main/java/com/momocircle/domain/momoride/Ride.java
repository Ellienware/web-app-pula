package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name="momoride_rides")
public class Ride extends BaseEntity {
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="driver_id", nullable=false) private Driver driver;
 @Column(nullable=false,length=160) private String origin;
 @Column(nullable=false,length=160) private String destination;
 @Column(name="departure_time",nullable=false) private LocalDateTime departureTime;
 @Column(name="available_seats",nullable=false) private int availableSeats;
 @Column(name="price_per_passenger",nullable=false,precision=19,scale=2) private BigDecimal pricePerPassenger;
 @Column(nullable=false,length=3) private String currency;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status=Status.OPEN;
 /** Optimistic-locking version. Two concurrent bookings racing for the last seat can no longer
     both win: the second writer's flush fails with an OptimisticLockingFailureException, which
     RideBookingService/MoMoRideService retries against a fresh read rather than trusting a JVM-local
     lock that would not hold across more than one application instance. */
 @jakarta.persistence.Version @Column(nullable=false) private long version;
 public Driver getDriver(){return driver;} public void setDriver(Driver v){driver=v;} public String getOrigin(){return origin;} public void setOrigin(String v){origin=v;}
 public String getDestination(){return destination;} public void setDestination(String v){destination=v;} public LocalDateTime getDepartureTime(){return departureTime;} public void setDepartureTime(LocalDateTime v){departureTime=v;}
 public int getAvailableSeats(){return availableSeats;} public void setAvailableSeats(int v){availableSeats=v;} public BigDecimal getPricePerPassenger(){return pricePerPassenger;} public void setPricePerPassenger(BigDecimal v){pricePerPassenger=v;}
 public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public Status getStatus(){return status;} public void setStatus(Status v){status=v;}
 public long getVersion(){return version;} public void setVersion(long v){version=v;}
 public enum Status { OPEN, FULL, IN_PROGRESS, COMPLETED, CANCELLED }
}
