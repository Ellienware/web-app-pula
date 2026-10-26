package com.momocircle.settlement;

import com.momocircle.domain.enums.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SettlementDtos {

    private SettlementDtos() {}

    public record AdjustmentRequest(@NotNull AdjustmentKind kind, @NotNull @Positive BigDecimal amount,
                                    @NotBlank @Size(max = 300) String reason, @NotBlank @Size(max = 100) String idempotencyKey) {}
    public record HoldRequest(@Size(max = 300) String reason) {}

    public record AdjustmentView(String id, AdjustmentKind kind, BigDecimal amount, String reason, String createdBy, Instant createdAt) {}

    /** Every money component, shown separately. Nothing is hidden inside another figure. */
    public record SettlementView(
            String id, SettlementSourceType sourceType, String sourceId, SellerKind sellerKind, String businessId, String businessName,
            SettlementType type, SettlementStatus status, String currency,
            BigDecimal gross, BigDecimal refunded, BigDecimal eligibleRevenue, BigDecimal commissionRate, BigDecimal commission,
            BigDecimal fixedFee, BigDecimal paymentFee, BigDecimal adjustments, BigDecimal net,
            /** Merchant-protection compensation paid to the restaurant (separate from the customer refund). */
            BigDecimal compensation,
            /** What PULA absorbed when it compensated a restaurant. */
            BigDecimal pulaOperationalLoss,
            Instant eligibleAt, Instant paidAt, String payoutProvider, String payoutReference, String holdReason,
            String failureReason, int payoutAttempts, Instant createdAt, List<AdjustmentView> adjustmentHistory) {}

    public record SourceTotals(SettlementSourceType sourceType, long count, BigDecimal gross, BigDecimal commission,
                               BigDecimal paymentFees, BigDecimal adjustments, BigDecimal refunds, BigDecimal sellerNet) {}
    public record StatusTotals(SettlementStatus status, long count, BigDecimal net) {}
    public record DriverPayoutTotals(PayoutStatus status, long count, BigDecimal amount) {}

    /** Admin marketplace finance: gross volume, PULA revenue (commission), fees, seller and driver money, PULA loss. */
    public record MarketplaceSummary(
            BigDecimal grossMarketplaceVolume, BigDecimal pulaCommissionRevenue, BigDecimal paymentFees, BigDecimal refunds,
            BigDecimal sellerNetTotal, BigDecimal pulaOperationalLoss,
            List<SourceTotals> bySource, List<StatusTotals> byStatus, List<DriverPayoutTotals> driverPayouts,
            long failedSettlements, long onHoldSettlements) {}

    public record SellerSummary(String businessId, List<SourceTotals> bySource, BigDecimal paid, BigDecimal owed,
                                BigDecimal onHold) {}

    public record ReconciliationRow(
            SettlementSourceType sourceType, String sourceId, ReconciliationState state, String detail,
            BigDecimal sourceTotal, BigDecimal paymentAmount, BigDecimal settlementGross, String paymentReference,
            String payoutReference, String settlementId) {}

    public record ReconciliationReport(Instant generatedAt, long checked, long matched, long problems, List<ReconciliationRow> rows) {}

    public record InsightsView(String summary, List<String> points, String notice) {}
}
