package com.momocircle.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: expire stale requests and emit idempotent contribution/payout notices. One bad stokvel never blocks others. */
@Component
public class StokvelMaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(StokvelMaintenanceTask.class);

    private final StokvelRequestService requestService;
    private final StokvelNoticeService noticeService;
    private final StokvelSupport support;

    public StokvelMaintenanceTask(StokvelRequestService requestService, StokvelNoticeService noticeService,
                                  StokvelSupport support) {
        this.requestService = requestService;
        this.noticeService = noticeService;
        this.support = support;
    }

    @Scheduled(fixedDelayString = "${app.stokvel.maintenance-ms:3600000}", initialDelayString = "${app.stokvel.initial-delay-ms:120000}")
    public void run() {
        try {
            int expired = requestService.expireDue();
            if (expired > 0) {
                log.info("Expired {} stokvel request(s)", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Stokvel request expiry failed", e);
        }
        for (String circleId : noticeService.stokvelCircleIds()) {
            try {
                noticeService.runFor(circleId, support.today());
            } catch (RuntimeException e) {
                log.warn("Stokvel notices failed for circle {}", circleId, e);
            }
        }
    }
}
