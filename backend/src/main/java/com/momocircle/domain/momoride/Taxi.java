package com.momocircle.domain.momoride;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

@Entity @Table(name="momoride_taxis", uniqueConstraints={
        @UniqueConstraint(name="uq_momoride_taxi_registration", columnNames="registration_number"),
        @UniqueConstraint(name="uq_momoride_taxi_public_id", columnNames="public_taxi_id"),
        @UniqueConstraint(name="uq_momoride_taxi_payment_code", columnNames="payment_code")})
public class Taxi extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="driver_id", nullable=false)
    private Driver driver;
    @Column(name="registration_number", nullable=false, length=32) private String registrationNumber;
    @Column(name="public_taxi_id", nullable=false, length=32) private String publicTaxiId;
    @Column(name="payment_code", nullable=false, length=12) private String paymentCode;
    /** Opaque public reference embedded in the taxi's QR code — deliberately not the payment code,
        so a QR image scanned/photographed in public doesn't also disclose the short code a passenger
        might otherwise type in by hand. */
    @Column(name="qr_token", length=64) private String qrToken;
    @Column(length=64) private String makeModel;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private TaxiStatus status=TaxiStatus.ACTIVE;
    public Driver getDriver(){return driver;} public void setDriver(Driver v){driver=v;}
    public String getRegistrationNumber(){return registrationNumber;} public void setRegistrationNumber(String v){registrationNumber=v;}
    public String getPublicTaxiId(){return publicTaxiId;} public void setPublicTaxiId(String v){publicTaxiId=v;}
    public String getPaymentCode(){return paymentCode;} public void setPaymentCode(String v){paymentCode=v;}
    public String getQrToken(){return qrToken;} public void setQrToken(String v){qrToken=v;}
    public String getMakeModel(){return makeModel;} public void setMakeModel(String v){makeModel=v;}
    public TaxiStatus getStatus(){return status;} public void setStatus(TaxiStatus v){status=v;}
    public enum TaxiStatus { ACTIVE, INACTIVE, SUSPENDED }
}
