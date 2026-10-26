package com.momocircle.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies bulk-group deadlines and syncs payment/fulfilment state. Separate bean so @Transactional proxies apply. */
@Component
public class BulkPurchaseMaintenanceTask {
    private static final Logger log = LoggerFactory.getLogger(BulkPurchaseMaintenanceTask.class);
    private final BulkPurchaseService service;

    public BulkPurchaseMaintenanceTask(BulkPurchaseService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.bulk.maintenance-ms:60000}")
    public void run() {
        for (String id : service.groupsNeedingMaintenance()) {
            try {
                service.maintain(id);
            } catch (Exception e) { // one bad group must not stop the sweep
                log.warn("Bulk group {} maintenance failed: {}", id, e.getMessage());
            }
        }
    }
}
