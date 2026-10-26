package com.momocircle.service;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.DeliveryEarningsAllocation;
import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryEarningsAllocationRepository;
import com.momocircle.repository.DeliveryRequestRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — connects a ready food order to the EXISTING PULA Ride delivery engine. It creates the
 * same {@link DeliveryRequest} the stock flow does; eligibility, acceptance (including the
 * duplicate-acceptance guard), pickup and delivery all stay in DeliveryRequestService. This class
 * only (1) dispatches automatically when the restaurant marks an order ready, (2) re-offers a
 * delivery when the assigned driver withdraws, (3) handles the unclaimed-delivery timers, and
 * (4) allocates the delivery fee 80% driver / 20% PULA.
 *
 * Must not depend on OrderService (OrderService calls this class).
 */
@Service
public class FoodDeliveryService {

    /** Share of a PULA Ride food-delivery fee that goes to the driver. PULA keeps the remainder. */
    public static final BigDecimal DRIVER_SHARE = new BigDecimal("0.80");

    private final DeliveryRequestRepository deliveryRepository;
    private final DeliveryEarningsAllocationRepository allocationRepository;
    private final NotificationService notificationService;
    private final FoodProperties properties;

    public FoodDeliveryService(DeliveryRequestRepository deliveryRepository,
                               DeliveryEarningsAllocationRepository allocationRepository,
                               NotificationService notificationService, FoodProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.allocationRepository = allocationRepository;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /** True only for food orders the customer chose PULA Ride delivery for and the restaurant accepted. */
    public static boolean needsPulaRideDelivery(Order order) {
        return order.isFood()
                && order.getFulfilmentMethod() == FulfilmentOption.MOMORIDE_DELIVERY
                && order.getRestaurantDecision() == RestaurantDecision.ACCEPTED;
    }

    // ------------------------------------------------------------------ automatic dispatch

    /**
     * Called in the same transaction that marks the order READY. Collection and own-rider orders are
     * ignored. Idempotent: delivery_requests.order_id is unique and an existing delivery is returned
     * as-is, so a retry or duplicate event never creates a second one. If it throws, the READY
     * change rolls back with it, so an order is never READY-for-delivery without a delivery.
     */
    @Transactional
    public Optional<DeliveryRequest> dispatchIfEligible(Order order) {
        if (!needsPulaRideDelivery(order)) return Optional.empty();
        Optional<DeliveryRequest> existing = deliveryRepository.findByOrderId(order.getId());
        if (existing.isPresent()) return existing;

        String pickup = pickupLocation(order);
        if (pickup == null || pickup.isBlank()) {
            throw ApiException.badRequest("Set your business address first so drivers know where to collect.");
        }
        if (order.getDeliveryAddress() == null || order.getDeliveryAddress().isBlank()) {
            throw ApiException.badRequest("This order has no delivery address.");
        }
        DeliveryRequest d = new DeliveryRequest();
        d.setOrder(order);
        d.setPickupLocation(pickup);
        d.setDropoffLocation(order.getDeliveryAddress());
        d.setEstimatedFee(order.getDeliveryFeeSnapshot());
        d.setStatus(DeliveryStatus.REQUESTED);
        d.setDriverSearchStartedAt(Instant.now()); // first driver-search attempt starts now
        d = deliveryRepository.save(d);
        notificationService.notify(order.getBuyer(), NotificationType.DELIVERY_UPDATE,
                "Your order is ready. We're finding a PULA Ride driver for you.", order.getId());
        return Optional.of(d);
    }

    private String pickupLocation(Order order) {
        if (order.getBranch() != null && order.getBranch().getAddress() != null
                && !order.getBranch().getAddress().isBlank()) {
            return order.getBranch().getAddress();
        }
        return order.getBusiness().getLocation();
    }

    // ------------------------------------------------------------------ driver withdrawal

    /**
     * The assigned driver pulled out before pickup. Instead of leaving a cancelled delivery on a
     * ready order, the same delivery goes back to REQUESTED for other eligible drivers. Mirrors
     * StockDeliveryService.redispatch, which also resets the status directly.
     */
    @Transactional
    public DeliveryRequest reofferAfterDriverWithdrawal(DeliveryRequest delivery) {
        if (delivery.getStatus() != DeliveryStatus.ACCEPTED) {
            throw ApiException.badRequest("Only a delivery that has not been picked up can be given back");
        }
        delivery.setStatus(DeliveryStatus.REQUESTED);
        delivery.setDriver(null);
        delivery.setAcceptedAt(null);
        delivery.setCancelledAt(null);
        delivery.setNoDriverNotifiedAt(null);
        // A new search attempt begins: the no-driver notification and the customer's cancellation
        // window both count from now, not from the original dispatch.
        delivery.setDriverSearchStartedAt(Instant.now());
        delivery = deliveryRepository.save(delivery);
        Order order = delivery.getOrder();
        notificationService.notify(order.getBuyer(), NotificationType.DELIVERY_UPDATE,
                "Your driver is no longer available. We're finding another one.", order.getId());
        notificationService.notify(order.getBusiness().getOwner(), NotificationType.DELIVERY_UPDATE,
                "The driver for an order withdrew. The delivery has been offered to other drivers.", order.getId());
        return delivery;
    }

    // ------------------------------------------------------------------ nobody took it

    /** Tells the customer once that no driver has taken their delivery yet. Returns how many were told. */
    @Transactional
    public int notifyUnclaimedDeliveries() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getNoDriverNotifyMinutes()));
        int n = 0;
        for (DeliveryRequest d : deliveryRepository.findUnclaimedFoodDeliveriesCreatedBefore(cutoff)) {
            // Only the notification marker changes; driverSearchStartedAt must stay put so the
            // cancellation window keeps counting from the start of the search.
            d.setNoDriverNotifiedAt(Instant.now());
            deliveryRepository.save(d);
            notificationService.notify(d.getOrder().getBuyer(), NotificationType.DELIVERY_UPDATE,
                    "No driver has taken your delivery yet. You can keep waiting"
                            + " or cancel for a full refund after " + properties.getNoDriverCancelMinutes() + " minutes.",
                    d.getOrder().getId());
            n++;
        }
        return n;
    }

    /**
     * Customer cancels a ready delivery order. Allowed only while the delivery has no driver AND has
     * been waiting at least the no-driver timeout. Cancels the delivery so no driver can still take it.
     */
    @Transactional
    public void cancelUnclaimedDeliveryForCustomer(Order order) {
        DeliveryRequest d = deliveryRepository.findByOrderId(order.getId())
                .orElseThrow(() -> ApiException.badRequest("This order has no delivery to cancel"));
        if (d.getStatus() != DeliveryStatus.REQUESTED) {
            throw ApiException.badRequest("A driver has already taken this delivery, so it can't be cancelled now");
        }
        Instant waitingSince = searchStartedAt(d);
        Instant allowedAt = waitingSince.plus(Duration.ofMinutes(properties.getNoDriverCancelMinutes()));
        if (Instant.now().isBefore(allowedAt)) {
            throw ApiException.badRequest("We're still looking for a driver. You can cancel if none is found within "
                    + properties.getNoDriverCancelMinutes() + " minutes.");
        }
        d.setStatus(DeliveryStatus.CANCELLED);
        d.setCancelledAt(Instant.now());
        try {
            // Flush now so a driver who accepted in the meantime (the @Version check) makes this fail
            // here, before the order is moved to a refund, rather than at commit time.
            deliveryRepository.saveAndFlush(d);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw ApiException.conflict("A driver has just taken this delivery, so it can't be cancelled now");
        }
    }

    /**
     * Start of the current driver-search attempt. Falls back to createdAt only for a delivery that has
     * no value (it pre-dates V32 and wasn't backfilled); never to updatedAt.
     */
    private static Instant searchStartedAt(DeliveryRequest d) {
        return d.getDriverSearchStartedAt() != null ? d.getDriverSearchStartedAt() : d.getCreatedAt();
    }

    // ------------------------------------------------------------------ 80/20 allocation

    /** driver = fee x 0.80 rounded half-down to the cent; PULA = fee - driver, so the two always sum to the fee. */
    public static BigDecimal[] split(BigDecimal fee) {
        BigDecimal driver = fee.multiply(DRIVER_SHARE).setScale(2, RoundingMode.HALF_DOWN);
        return new BigDecimal[] {driver, fee.subtract(driver)};
    }

    /**
     * Records the split for a delivered PULA Ride food delivery, from the ORDER's delivery-fee
     * snapshot (never a client value). One row per delivery: a repeat call returns the existing row.
     * A zero fee records nothing. Orders on the restaurant's own riders never reach here.
     */
    @Transactional
    public Optional<DeliveryEarningsAllocation> allocate(DeliveryRequest delivery) {
        Order order = delivery.getOrder();
        if (order == null || !order.isFood() || order.getFulfilmentMethod() != FulfilmentOption.MOMORIDE_DELIVERY) {
            return Optional.empty();
        }
        Optional<DeliveryEarningsAllocation> existing = allocationRepository.findByDeliveryRequestId(delivery.getId());
        if (existing.isPresent()) return existing;
        BigDecimal fee = order.getDeliveryFeeSnapshot();
        if (fee == null || fee.signum() <= 0) return Optional.empty();
        BigDecimal[] parts = split(fee);
        DeliveryEarningsAllocation a = new DeliveryEarningsAllocation();
        a.setDeliveryRequest(delivery);
        a.setDeliveryFeeSnapshot(fee);
        a.setDriverShare(parts[0]);
        a.setPulaShare(parts[1]);
        a.setSplitPolicy(DeliveryEarningsAllocation.POLICY_PULA_RIDE_80_20);
        return Optional.of(allocationRepository.save(a));
    }
}
