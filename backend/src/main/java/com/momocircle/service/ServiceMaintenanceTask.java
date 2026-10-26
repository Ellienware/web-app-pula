package com.momocircle.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: expire stale service requests and quotes. */
@Component
public class ServiceMaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(ServiceMaintenanceTask.class);
    private final ServiceRequestService requestService;

    public ServiceMaintenanceTask(ServiceRequestService requestService) {
        this.requestService = requestService;
    }

    @Scheduled(fixedDelayString = "${app.services.maintenance-ms:3600000}", initialDelayString = "${app.services.initial-delay-ms:180000}")
    public void run() {
        try {
            int n = requestService.expireDue();
            if (n > 0) log.info("Expired {} service request(s)", n);
        } catch (RuntimeException e) {
            log.warn("Service expiry failed", e);
        }
    }
}
