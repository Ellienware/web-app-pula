package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryPoolMemberRepository;
import com.momocircle.repository.DeliveryRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands a ready stock order to the shared PULA Ride delivery engine. WHOLESALER_DELIVERY
 * jobs are reserved for the supplier's own driver pool; PULA_RIDE_DELIVERY jobs are open
 * to independent drivers. The supplier never picks a driver — a driver accepts.
 */
@Service
public class StockDeliveryService {

    private final DeliveryRequestRepository deliveryRepository;
    private final DeliveryPoolMemberRepository poolRepository;

    public StockDeliveryService(DeliveryRequestRepository deliveryRepository,
                                DeliveryPoolMemberRepository poolRepository) {
        this.deliveryRepository = deliveryRepository;
        this.poolRepository = poolRepository;
    }

    /** Validates that dispatch is possible, WITHOUT creating anything. Call before changing order state. */
    public void requireDispatchable(StockOrder order) {
        if (order.getFulfilmentMethod() == StockFulfilmentOption.SELF_COLLECTION) return;
        String pickup = order.getSupplierBusiness().getLocation();
        if (pickup == null || pickup.isBlank()) {
            throw ApiException.badRequest("Set your business location first so drivers know where to collect.");
        }
        if (order.getDeliveryAddress() == null || order.getDeliveryAddress().isBlank()) {
            throw ApiException.badRequest("This order has no delivery address.");
        }
        if (order.getFulfilmentMethod() == StockFulfilmentOption.WHOLESALER_DELIVERY
                && poolRepository.countByBusinessIdAndActiveTrue(order.getSupplierBusiness().getId()) == 0) {
            throw ApiException.badRequest("Your driver pool has no active drivers. Add drivers to your pool first.");
        }
    }

    /** Re-offers a delivery that was cancelled (e.g. the driver dropped out) to the eligible drivers again. */
    @Transactional
    public DeliveryRequest redispatch(StockOrder order) {
        DeliveryRequest d = deliveryRepository.findByStockOrderId(order.getId())
                .orElseThrow(() -> ApiException.badRequest("This order has no delivery to re-send"));
        if (d.getStatus() != DeliveryStatus.CANCELLED) {
            throw ApiException.badRequest("Only a cancelled delivery can be sent out again");
        }
        requireDispatchable(order);
        d.setStatus(DeliveryStatus.REQUESTED);
        d.setDriver(null);
        d.setAcceptedAt(null);
        d.setCancelledAt(null);
        return deliveryRepository.save(d);
    }

    @Transactional
    public DeliveryRequest dispatch(StockOrder order) {
        return deliveryRepository.findByStockOrderId(order.getId()).orElseGet(() -> {
            requireDispatchable(order);
            DeliveryRequest d = new DeliveryRequest();
            d.setStockOrder(order);
            d.setPickupLocation(order.getSupplierBusiness().getLocation());
            d.setDropoffLocation(order.getDeliveryAddress());
            d.setEstimatedFee(order.getDeliveryFeeSnapshot());
            d.setStatus(DeliveryStatus.REQUESTED);
            if (order.getFulfilmentMethod() == StockFulfilmentOption.WHOLESALER_DELIVERY) {
                d.setPoolBusiness(order.getSupplierBusiness());
            }
            return deliveryRepository.save(d);
        });
    }
}
