package com.momocircle.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically finishes or releases split-bill settlements left in PROCESSING. Safe on every node and to repeat. */
@Component
public class ExpenseSettlementSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpenseSettlementSweeper.class);
    private final ExpenseSettlementService service;

    public ExpenseSettlementSweeper(ExpenseSettlementService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.wallet.sweep-ms:60000}", initialDelayString = "${app.wallet.sweep-initial-delay-ms:120000}")
    public void run() {
        try {
            int n = service.sweep();
            if (n > 0) log.info("Split-bill settlement sweep changed {} share(s)", n);
        } catch (RuntimeException e) {
            log.error("Split-bill settlement sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
