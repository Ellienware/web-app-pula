package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.User;
import jakarta.persistence.*;

/**
 * A 1-5 star rating a ride's passenger leaves for the driver after the ride is
 * COMPLETED. Deliberately a dedicated entity rather than an extension of the
 * MoMoMarket {@code Review} — Review's {@code order_id} column is a mandatory,
 * non-null foreign key to a MoMoMarket {@code Order}, so reusing it for a
 * ride would mean either a nullable-Order schema change (weakening an existing
 * invariant every other Review relies on) or fabricating a fake Order per
 * ride. Neither is "genuinely clean," so this stays separate.
 */
@Entity @Table(name="momoride_ride_ratings", uniqueConstraints=@UniqueConstraint(name="uq_momoride_rating_ride_rater", columnNames={"ride_id","rater_id"}))
public class RideRating extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="ride_id", nullable=false) private Ride ride;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="rater_id", nullable=false) private User rater;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="driver_id", nullable=false) private Driver driver;
    @Column(nullable=false) private int rating;
    @Column(length=500) private String comment;

    public Ride getRide(){return ride;} public void setRide(Ride v){ride=v;}
    public User getRater(){return rater;} public void setRater(User v){rater=v;}
    public Driver getDriver(){return driver;} public void setDriver(Driver v){driver=v;}
    public int getRating(){return rating;} public void setRating(int v){rating=v;}
    public String getComment(){return comment;} public void setComment(String v){comment=v;}
}
