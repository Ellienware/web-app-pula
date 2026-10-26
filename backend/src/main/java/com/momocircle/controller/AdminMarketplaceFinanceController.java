package com.momocircle.controller;

import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.security.CurrentUserService;
import com.momocircle.settlement.MarketplaceReconciliationService;
import com.momocircle.settlement.MarketplaceSettlementService;
import com.momocircle.settlement.SettlementDtos.*;
import com.momocircle.settlement.SettlementInsightService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin visibility and the few permitted interventions. Every method requires an ADMIN. By design there is NO endpoint to
 * mark a settlement paid, change an amount directly, issue a refund, or override a reconciliation result: admins can hold,
 * release, and add an immutable reasoned adjustment, and all of it is audit-logged.
 */
@RestController
@RequestMapping("/api/admin/marketplace")
public class AdminMarketplaceFinanceController {

    private final CurrentUserService currentUser;
    private final MarketplaceSettlementService settlements;
    private final MarketplaceReconciliationService reconciliation;
    private final SettlementInsightService insights;

    public AdminMarketplaceFinanceController(CurrentUserService currentUser, MarketplaceSettlementService settlements,
                                             MarketplaceReconciliationService reconciliation, SettlementInsightService insights) {
        this.currentUser = currentUser;
        this.settlements = settlements;
        this.reconciliation = reconciliation;
        this.insights = insights;
    }

    @GetMapping("/summary")
    public MarketplaceSummary summary() {
        currentUser.requireAdmin();
        return settlements.summary();
    }

    @GetMapping("/settlements")
    public List<SettlementView> list(@RequestParam(required = false) List<SettlementStatus> status,
                                     @RequestParam(required = false) SettlementSourceType source,
                                     @RequestParam(defaultValue = "50") int limit) {
        currentUser.requireAdmin();
        return settlements.adminList(status, source, limit);
    }

    @GetMapping("/settlements/failed")
    public List<SettlementView> failed(@RequestParam(defaultValue = "50") int limit) {
        currentUser.requireAdmin();
        return settlements.adminList(List.of(SettlementStatus.FAILED), null, limit);
    }

    @GetMapping("/settlements/{id}")
    public SettlementView one(@PathVariable String id) {
        currentUser.requireAdmin();
        return settlements.adminGet(id);
    }

    @GetMapping("/settlements/{id}/explain")
    public InsightsView explain(@PathVariable String id) {
        return insights.explainSettlement(currentUser.requireAdmin(), true, id);
    }

    @PostMapping("/settlements/{id}/hold")
    public SettlementView hold(@PathVariable String id, @Valid @RequestBody(required = false) HoldRequest req) {
        return settlements.hold(currentUser.requireAdmin(), id, req == null ? null : req.reason());
    }

    @PostMapping("/settlements/{id}/release")
    public SettlementView release(@PathVariable String id) {
        return settlements.release(currentUser.requireAdmin(), id);
    }

    @PostMapping("/settlements/{id}/adjustments")
    public SettlementView adjust(@PathVariable String id, @Valid @RequestBody AdjustmentRequest req) {
        return settlements.adjust(currentUser.requireAdmin(), id, req.kind(), req.amount(), req.reason(), req.idempotencyKey());
    }

    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(@RequestParam(defaultValue = "200") int limit) {
        currentUser.requireAdmin();
        return reconciliation.reconcile(limit);
    }

    @GetMapping("/reconciliation/{source}/{sourceId}/explain")
    public InsightsView explainReconciliation(@PathVariable SettlementSourceType source, @PathVariable String sourceId) {
        currentUser.requireAdmin();
        return insights.explainReconciliation(source, sourceId);
    }
}
