package com.momocircle.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic housekeeping for stock orders (separate bean so @Transactional proxies apply). */
@Component
public class StockOrderMaintenanceTask {
    private final StockPaymentService payments;
    private final PayoutService payouts;

    public StockOrderMaintenanceTask(StockPaymentService payments, PayoutService payouts) {
        this.payments = payments;
        this.payouts = payouts;
    }

    @Scheduled(fixedDelayString = "${app.stock.maintenance-ms:60000}")
    public void run() {
        payments.reconcileProcessingOrders();
        payments.reconcileConsumerPayments();
        payments.expireUnpaidOrders();
        payouts.retryFailedPayouts();
    }
}
