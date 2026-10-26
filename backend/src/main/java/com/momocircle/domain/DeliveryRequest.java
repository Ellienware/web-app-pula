package com.momocircle.domain;

import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.momoride.Driver;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A MoMoRide delivery job for one MoMoMarket order (brief §14) — the one
 * place this app deliberately lets the two otherwise-separate domains
 * touch. Created only when the order's business supports and the order was
 * placed with FulfilmentOption.MOMORIDE_DELIVERY; the rest of MoMoRide
 * (Ride/RideBooking/taxi payments) is completely untouched by this entity
 * - a delivery is not a Ride, has no seats, and doesn't go through ride
 * booking at all. It only ever references a Driver by id once one accepts.
 */
@Entity
@Table(name = "delivery_requests")
public class DeliveryRequest extends BaseEntity {

    /** Consumer-order delivery. Exactly one of order / stockOrder is set (DB check constraint, V22). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", unique = true)
    private Order order;

    /** B2B stock-order delivery (PULA Ride is the shared dispatch engine for both). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_order_id", unique = true)
    private StockOrder stockOrder;

    /**
     * When set, only active members of this business's driver pool may see/accept
     * the job (WHOLESALER_DELIVERY). When null the job is open to independent drivers.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_business_id")
    private Business poolBusiness;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeliveryStatus status = DeliveryStatus.REQUESTED;

    /** Snapshot of the business's location at request time - see Business.location. */
    @Column(nullable = false)
    private String pickupLocation;

    /** Where the buyer wants it delivered. Required to request delivery. */
    @Column(nullable = false)
    private String dropoffLocation;

    /**
     * Optional estimated delivery fee. Null means "not estimated" - never
     * fabricate a number here; see the brief's own instruction (section 30)
     * to clearly distinguish LIVE/DEMO/ESTIMATED figures rather than
     * present an invented one as real.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal estimatedFee;

    private Instant acceptedAt;
    private Instant pickedUpAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    /**
     * PULA Food: when the CURRENT driver-search attempt began. Set when the restaurant marks the order
     * READY and dispatch starts, and reset when an accepted driver withdraws and the delivery goes back
     * out. This (never updatedAt, which changes on every write) drives the no-driver notification and
     * the customer's cancellation window. Null on non-food deliveries and on rows that pre-date V32.
     */
    private Instant driverSearchStartedAt;

    /** PULA Food: set once the customer has been told no driver has taken this delivery yet. Does not move driverSearchStartedAt. */
    private Instant noDriverNotifiedAt;

    /** Optimistic-locking version - see Ride's own javadoc for why this matters for two drivers racing to accept. */
    @Version
    @Column(nullable = false)
    private long version;

    public StockOrder getStockOrder() { return stockOrder; }
    public void setStockOrder(StockOrder stockOrder) { this.stockOrder = stockOrder; }
    public Business getPoolBusiness() { return poolBusiness; }
    public void setPoolBusiness(Business poolBusiness) { this.poolBusiness = poolBusiness; }

    /** The business that hands the goods to the driver (seller for consumer orders, supplier for stock orders). */
    public Business sellerBusiness() {
        return order != null ? order.getBusiness() : stockOrder.getSupplierBusiness();
    }
    /** Id of the commercial order this delivery serves, whichever kind it is. */
    public String referenceOrderId() {
        return order != null ? order.getId() : stockOrder.getId();
    }
    public String currency() {
        return order != null ? order.getCurrency() : stockOrder.getCurrency();
    }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public String getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }
    public String getDropoffLocation() { return dropoffLocation; }
    public void setDropoffLocation(String dropoffLocation) { this.dropoffLocation = dropoffLocation; }
    public Instant getDriverSearchStartedAt() { return driverSearchStartedAt; }
    public void setDriverSearchStartedAt(Instant driverSearchStartedAt) { this.driverSearchStartedAt = driverSearchStartedAt; }
    public Instant getNoDriverNotifiedAt() { return noDriverNotifiedAt; }
    public void setNoDriverNotifiedAt(Instant noDriverNotifiedAt) { this.noDriverNotifiedAt = noDriverNotifiedAt; }
    public BigDecimal getEstimatedFee() { return estimatedFee; }
    public void setEstimatedFee(BigDecimal estimatedFee) { this.estimatedFee = estimatedFee; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(Instant pickedUpAt) { this.pickedUpAt = pickedUpAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
