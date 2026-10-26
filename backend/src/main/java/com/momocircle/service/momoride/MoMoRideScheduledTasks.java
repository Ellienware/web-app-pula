package com.momocircle.service.momoride;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically expires OPEN TaxiPaymentRequests past their TTL — see MoMoRideService#expireStalePaymentRequests. */
@Component
public class MoMoRideScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(MoMoRideScheduledTasks.class);

    private final MoMoRideService moMoRideService;

    public MoMoRideScheduledTasks(MoMoRideService moMoRideService) {
        this.moMoRideService = moMoRideService;
    }

    @Scheduled(fixedDelayString = "${app.momoride.payment-request-expiry-check-ms:60000}")
    public void expireStalePaymentRequests() {
        int expired = moMoRideService.expireStalePaymentRequests();
        if (expired > 0) {
            log.info("Expired {} stale MoMoRide payment request(s)", expired);
        }
    }
}
