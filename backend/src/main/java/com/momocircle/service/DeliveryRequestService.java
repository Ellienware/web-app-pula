package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.dto.DeliveryDtos.DeliveryRequestResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryRequestRepository;
import com.momocircle.repository.momoride.DriverRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The one place MoMoMarket and MoMoRide are allowed to touch (brief §14).
 * Depends on {@code OrderService} (to read/complete orders) and
 * {@code DriverRepository} (to resolve which Driver a User is) — it does
 * NOT depend on RideService/RideBookingService/TaxiPaymentRequest or
 * anything else in the momoride package, and nothing in momoride depends
 * on this class or on MoMoMarket at all. A delivery is not a Ride: it has
 * no seats, no booking, and doesn't touch RideSeatConcurrencyService.
 */
@Service
public class DeliveryRequestService {

    private final DeliveryRequestRepository deliveryRequestRepository;
    private final OrderService orderService;
    private final DriverRepository driverRepository;
    private final NotificationService notificationService;
    private final DeliveryAcceptanceConcurrencyService acceptanceConcurrencyService;
    private final StockOrderService stockOrderService;
    private final PayoutService payoutService;
    private final com.momocircle.repository.DeliveryPoolMemberRepository poolMemberRepository;
    private final FoodDeliveryService foodDeliveryService;

    public DeliveryRequestService(DeliveryRequestRepository deliveryRequestRepository, OrderService orderService,
                                   DriverRepository driverRepository, NotificationService notificationService,
                                   DeliveryAcceptanceConcurrencyService acceptanceConcurrencyService,
                                   StockOrderService stockOrderService, PayoutService payoutService,
                                   com.momocircle.repository.DeliveryPoolMemberRepository poolMemberRepository,
                                   FoodDeliveryService foodDeliveryService) {
        this.deliveryRequestRepository = deliveryRequestRepository;
        this.orderService = orderService;
        this.driverRepository = driverRepository;
        this.notificationService = notificationService;
        this.acceptanceConcurrencyService = acceptanceConcurrencyService;
        this.stockOrderService = stockOrderService;
        this.payoutService = payoutService;
        this.poolMemberRepository = poolMemberRepository;
        this.foodDeliveryService = foodDeliveryService;
    }

    /**
     * Seller requests MoMoRide delivery for an order they've already set
     * fulfilmentMethod = MOMORIDE_DELIVERY on (see OrderService#setFulfilment).
     * Only the order's seller can do this — matching brief §14's "a seller
     * should be able to select 'Deliver using MoMoRide'."
     */
    @Transactional
    public DeliveryRequestResponse request(User seller, String orderId) {
        Order order = orderService.requireOrderEntity(orderId);
        if (!order.getBusiness().getOwner().getId().equals(seller.getId())) {
            throw ApiException.forbidden("You cannot request delivery for this order");
        }
        if (order.isFood()) {
            // PULA Food: the customer already chose PULA Ride delivery at checkout, and driver matching
            // starts by itself when the restaurant marks the order ready. A manual request could
            // dispatch a driver before the food exists.
            throw ApiException.badRequest(
                    "Delivery for food orders starts automatically when you mark the order ready.");
        }
        if (order.getFulfilmentMethod() != FulfilmentOption.MOMORIDE_DELIVERY) {
            throw ApiException.badRequest(
                    "This order isn't set to PULA Ride delivery — set fulfilment first.");
        }
        if (order.getDeliveryAddress() == null || order.getDeliveryAddress().isBlank()) {
            throw ApiException.badRequest("This order has no delivery address.");
        }
        if (deliveryRequestRepository.findByOrderId(orderId).isPresent()) {
            throw ApiException.conflict("A delivery has already been requested for this order");
        }
        if (order.getBusiness().getLocation() == null || order.getBusiness().getLocation().isBlank()) {
            throw ApiException.badRequest(
                    "This seller hasn't set a business location yet, so a pickup point can't be set.");
        }

        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setPickupLocation(order.getBusiness().getLocation());
        delivery.setDropoffLocation(order.getDeliveryAddress());
        delivery.setStatus(DeliveryStatus.REQUESTED);

        return toResponse(deliveryRequestRepository.save(delivery));
    }

    @Transactional(readOnly = true)
    public DeliveryRequestResponse getForOrder(User actor, String orderId) {
        Order order = orderService.requireOrderEntity(orderId);
        boolean isBuyer = order.getBuyer().getId().equals(actor.getId());
        boolean isSeller = order.getBusiness().getOwner().getId().equals(actor.getId());
        if (!isBuyer && !isSeller) {
            throw ApiException.forbidden("You cannot view this delivery");
        }
        DeliveryRequest delivery = deliveryRequestRepository.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("No delivery has been requested for this order"));
        return toResponse(delivery);
    }

    /**
     * Drivers browse open delivery jobs — read-only, like Ride search.
     * Restricted to users with a Driver profile: unlike a Ride's
     * origin/destination (already effectively public once posted), a
     * delivery's dropoffLocation is a real customer address, so this
     * can't be open to any authenticated user the way ride search is.
     */
    @Transactional(readOnly = true)
    public Page<DeliveryRequestResponse> browseOpen(User driverUser, Pageable pageable) {
        Driver driver = requireDriver(driverUser);
        // Independent jobs plus jobs reserved for pools this driver actively belongs to.
        return deliveryRequestRepository.findOpenEligibleForDriver(DeliveryStatus.REQUESTED, driver.getId(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<DeliveryRequestResponse> myDeliveries(User driverUser, Pageable pageable) {
        Driver driver = requireDriver(driverUser);
        return deliveryRequestRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId(), pageable)
                .map(this::toResponse);
    }

    /**
     * A driver claims an open delivery. Delegates the actual concurrency-safe
     * write to {@link DeliveryAcceptanceConcurrencyService} (a separate bean —
     * see its own javadoc for why that's required, not optional). After that
     * call returns, re-fetches the delivery here rather than using the entity
     * that call returned directly: the concurrency service sets the driver via
     * an id-only reference object (deliberately, to avoid a second repository
     * dependency there), so that returned entity's {@code driver.user} is not
     * loaded and {@link #toResponse} would NPE on it. A fresh read gives a
     * properly hydrated entity instead.
     */
    @Transactional
    public DeliveryRequestResponse accept(User driverUser, String deliveryRequestId) {
        Driver driver = requireDriver(driverUser);
        DeliveryRequest candidate = requireById(deliveryRequestId);
        // Pool-reserved jobs may only be accepted by active members of that pool.
        if (candidate.getPoolBusiness() != null && !poolMemberRepository
                .existsByBusinessIdAndDriverIdAndActiveTrue(candidate.getPoolBusiness().getId(), driver.getId())) {
            throw ApiException.forbidden("This delivery is reserved for another driver pool.");
        }
        acceptanceConcurrencyService.acceptWithRetry(deliveryRequestId, driver.getId());
        DeliveryRequest delivery = requireById(deliveryRequestId);
        notificationService.notify(delivery.sellerBusiness().getOwner(), NotificationType.DELIVERY_UPDATE,
                "A driver accepted the delivery for your order.", delivery.referenceOrderId());
        return toResponse(delivery);
    }

    @Transactional
    public DeliveryRequestResponse markPickedUp(User driverUser, String deliveryRequestId) {
        DeliveryRequest delivery = requireOwnedByDriver(driverUser, deliveryRequestId);
        requireTransition(delivery, DeliveryStatus.PICKED_UP);
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(Instant.now());
        delivery = deliveryRequestRepository.save(delivery);
        if (delivery.getStockOrder() != null) {
            stockOrderService.markOutForDelivery(delivery.getStockOrder());
        }
        return toResponse(delivery);
    }

    /** Delivery confirmed — this is the one call that also completes the underlying order. */
    @Transactional
    public DeliveryRequestResponse markDelivered(User driverUser, String deliveryRequestId) {
        DeliveryRequest delivery = requireOwnedByDriver(driverUser, deliveryRequestId);
        requireTransition(delivery, DeliveryStatus.DELIVERED);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now());
        delivery = deliveryRequestRepository.save(delivery);

        if (delivery.getOrder() != null) {
            orderService.markCompletedByDelivery(delivery.getOrder());
            notificationService.notify(delivery.getOrder().getBuyer(), NotificationType.DELIVERY_UPDATE,
                    "Your order has been delivered.", delivery.getOrder().getId());
            if (delivery.getOrder().isFood()) {
                // PULA Food: split the order's delivery-fee snapshot 80/20 and pay the driver's share.
                // A payout failure is recorded, never thrown, so it can't undo the completed delivery.
                final DeliveryRequest completedDelivery = delivery; // `delivery` is reassigned above, so a lambda cannot capture it
                foodDeliveryService.allocate(completedDelivery).ifPresent(a ->
                        payoutService.createForCompletedDelivery(completedDelivery, a.getDriverShare()));
            }
        } else {
            stockOrderService.markDeliveredByDelivery(delivery.getStockOrder());
            notificationService.notify(delivery.getStockOrder().getBuyerBusiness().getOwner(),
                    NotificationType.DELIVERY_UPDATE, "Your stock order has been delivered.",
                    delivery.getStockOrder().getId());
            // Driver earnings: a separate PULA -> driver payout, never a customer payment.
            payoutService.createForCompletedDelivery(delivery);
        }
        return toResponse(delivery);
    }

    /** Either the requesting seller or the assigned driver can cancel before pickup. */
    @Transactional
    public DeliveryRequestResponse cancel(User actor, String deliveryRequestId) {
        DeliveryRequest delivery = requireById(deliveryRequestId);
        boolean isSeller = delivery.sellerBusiness().getOwner().getId().equals(actor.getId());
        boolean isDriver = delivery.getDriver() != null && delivery.getDriver().getUser().getId().equals(actor.getId());
        if (!isSeller && !isDriver) {
            throw ApiException.forbidden("You cannot cancel this delivery.");
        }
        if (delivery.getOrder() != null && delivery.getOrder().isFood()) {
            // PULA Food: an auto-dispatched delivery is never left cancelled on a ready order.
            // The assigned driver withdrawing puts it back out to other drivers; nobody else cancels
            // it here (the customer cancels through the order, subject to the refund rules).
            if (isDriver && delivery.getStatus() == DeliveryStatus.ACCEPTED) {
                return toResponse(foodDeliveryService.reofferAfterDriverWithdrawal(delivery));
            }
            throw ApiException.badRequest("This food delivery can't be cancelled here.");
        }
        requireTransition(delivery, DeliveryStatus.CANCELLED);
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledAt(Instant.now());
        return toResponse(deliveryRequestRepository.save(delivery));
    }

    private void requireTransition(DeliveryRequest delivery, DeliveryStatus next) {
        if (!delivery.getStatus().canTransitionTo(next)) {
            throw ApiException.badRequest("Cannot move delivery from " + delivery.getStatus() + " to " + next + ".");
        }
    }

    private DeliveryRequest requireOwnedByDriver(User driverUser, String deliveryRequestId) {
        Driver driver = requireDriver(driverUser);
        DeliveryRequest delivery = requireById(deliveryRequestId);
        if (delivery.getDriver() == null || !delivery.getDriver().getId().equals(driver.getId())) {
            throw ApiException.forbidden("You are not the driver for this delivery.");
        }
        return delivery;
    }

    private Driver requireDriver(User user) {
        return driverRepository.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.forbidden("You need a driver profile to do this."));
    }

    private DeliveryRequest requireById(String id) {
        return deliveryRequestRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Delivery request not found"));
    }

    private DeliveryRequestResponse toResponse(DeliveryRequest d) {
        return new DeliveryRequestResponse(
                d.getId(), d.referenceOrderId(), d.sellerBusiness().getName(),
                d.getDriver() == null ? null : d.getDriver().getId(),
                d.getDriver() == null ? null : d.getDriver().getUser().getFullName(),
                d.getStatus(), d.getPickupLocation(), d.getDropoffLocation(), d.getEstimatedFee(),
                d.currency(), d.getAcceptedAt(), d.getPickedUpAt(), d.getDeliveredAt(),
                d.getCreatedAt());
    }
}
