package com.momocircle.service.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically expires overdue payment requests. Expiry is also enforced lazily on every read and on every payment. */
@Component
public class PaymentRequestSweeper {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestSweeper.class);
    private final PaymentRequestService service;

    public PaymentRequestSweeper(PaymentRequestService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.wallet.sweep-ms:60000}", initialDelayString = "${app.wallet.sweep-initial-delay-ms:120000}")
    public void run() {
        try {
            int n = service.sweep();
            if (n > 0) log.info("Wallet payment-request sweep changed {} request(s)", n);
        } catch (RuntimeException e) {
            log.error("Wallet payment-request sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
