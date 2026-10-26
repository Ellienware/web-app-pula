package com.momocircle.service;

import com.momocircle.domain.Order;
import com.momocircle.repository.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PULA Food housekeeping (separate bean so @Transactional proxies apply): rejects paid food orders
 * the restaurant never answered, drives refunds and restaurant settlement payouts to completion, and tells customers once when
 * no driver has taken their delivery. Each order is handled
 * independently so one failure cannot block the rest.
 */
@Component
public class FoodOrderMaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(FoodOrderMaintenanceTask.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderRefundService refundService;
    private final FoodDeliveryService foodDelivery;
    private final FoodSettlementService settlementService;

    public FoodOrderMaintenanceTask(OrderRepository orderRepository, OrderService orderService,
                                    OrderRefundService refundService, FoodDeliveryService foodDelivery,
                                    FoodSettlementService settlementService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.refundService = refundService;
        this.foodDelivery = foodDelivery;
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelayString = "${app.food.maintenance-ms:30000}")
    public void run() {
        for (Order o : orderRepository.findFoodOrdersPastAcceptDeadline(Instant.now())) {
            try {
                orderService.autoRejectIfExpired(o.getId());
            } catch (RuntimeException e) {
                log.warn("Auto-reject of order {} failed, will retry: {}", o.getId(), e.getMessage());
            }
        }
        // Refunds: start/drive orders owed a refund, then poll refunds the provider is still processing. Each item is
        // its own transaction (called through the proxy), so one failure never blocks or rolls back the rest.
        for (String orderId : refundService.orderIdsAwaitingRefund()) {
            try {
                refundService.attemptRefundById(orderId);
            } catch (RuntimeException e) {
                log.warn("Refund for order {} errored, will retry: {}", orderId, e.getMessage());
            }
        }
        for (String refundId : refundService.inFlightRefundIds()) {
            try {
                refundService.advanceById(refundId);
            } catch (RuntimeException e) {
                log.warn("Refund {} status check errored, will retry: {}", refundId, e.getMessage());
            }
        }
        // Restaurant settlements: pay what is due (idempotent, atomically claimed), retry failures, re-drive stuck ones.
        try {
            for (FoodSettlementService.DuePayout due : settlementService.dueForPayout()) {
                try {
                    settlementService.pay(due.settlementId(), due.stuck());
                } catch (RuntimeException e) {
                    log.warn("Settlement {} payout errored, will retry: {}", due.settlementId(), e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Settlement payout pass failed, will retry: {}", e.getMessage());
        }
        try {
            foodDelivery.notifyUnclaimedDeliveries();
        } catch (RuntimeException e) {
            log.warn("Unclaimed-delivery notice failed, will retry: {}", e.getMessage());
        }
    }
}
