package com.momocircle.settlement;

import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.settlement.SettlementDtos.InsightsView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY explanations for the assistant and the UI: how a settlement was calculated, and what a reconciliation
 * discrepancy means. Its only dependencies are read paths, so nothing built on it can change a settlement, approve a payout,
 * mark anything paid, issue a refund or override reconciliation.
 */
@Service
public class SettlementInsightService {

    static final String NOTICE = "This is an explanation only. It cannot change a settlement, approve a payout, issue a refund or override a reconciliation.";

    private final RestaurantSettlementRepository settlements;
    private final MarketplaceReconciliationService reconciliation;

    public SettlementInsightService(RestaurantSettlementRepository settlements, MarketplaceReconciliationService reconciliation) {
        this.settlements = settlements;
        this.reconciliation = reconciliation;
    }

    /** Explains one settlement to its seller (own business only) or to an admin. */
    @Transactional(readOnly = true)
    public InsightsView explainSettlement(User viewer, boolean admin, String settlementId) {
        RestaurantSettlement s = settlements.findById(settlementId).orElseThrow(() -> ApiException.notFound("Settlement not found"));
        if (!admin && !s.getBusiness().getOwner().getId().equals(viewer.getId())) {
            throw ApiException.notFound("Settlement not found");
        }
        String c = s.getCurrency();
        List<String> pts = new ArrayList<>();
        pts.add("Gross sales: " + c + " " + s.getGrossAmount() + " (delivery fees are never part of seller revenue).");
        if (s.getRefundAmount().signum() > 0) pts.add("Refunds recorded: " + c + " " + s.getRefundAmount() + ", deducted before commission.");
        pts.add("Commission: " + c + " " + s.getCommissionAmount() + " at " + s.getCommissionRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "% of " + s.getEligibleRevenue() + ".");
        if (s.getOtherFees().signum() > 0) pts.add("Fixed fee: " + c + " " + s.getOtherFees() + ".");
        if (s.getPaymentFeeAmount().signum() > 0) pts.add("Payment fee: " + c + " " + s.getPaymentFeeAmount() + ".");
        if (s.getAdjustmentAmount().signum() != 0) pts.add("Adjustments: " + c + " " + s.getAdjustmentAmount() + " (each is a permanent, reasoned record).");
        pts.add("You receive: " + c + " " + s.getNetAmount() + ". Status: " + s.getStatus() + ".");
        if (s.getHoldReason() != null) pts.add("On hold: " + s.getHoldReason());
        return new InsightsView("Settlement for " + s.getSourceType() + " " + s.getSourceId(), pts, NOTICE);
    }

    /** Admin only (the controller enforces it): explains why a sale does or does not reconcile. */
    @Transactional(readOnly = true)
    public InsightsView explainReconciliation(SettlementSourceType type, String sourceId) {
        var row = reconciliation.reconcileOne(type, sourceId);
        List<String> pts = new ArrayList<>();
        pts.add(row.detail());
        if (row.sourceTotal() != null) pts.add("Sale total: " + row.sourceTotal());
        if (row.paymentAmount() != null) pts.add("Payment amount: " + row.paymentAmount() + " (reference " + row.paymentReference() + ")");
        if (row.settlementGross() != null) pts.add("Settlement gross: " + row.settlementGross());
        if (row.payoutReference() != null) pts.add("Payout reference: " + row.payoutReference());
        return new InsightsView("Reconciliation state: " + row.state(), pts, NOTICE);
    }
}
