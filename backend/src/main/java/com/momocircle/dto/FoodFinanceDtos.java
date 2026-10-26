package com.momocircle.dto;

import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.RestaurantSettlement;

import java.math.BigDecimal;
import java.time.Instant;

/** Admin-facing views of refunds and restaurant settlements: read-only, so admins can see why money moved. */
public final class FoodFinanceDtos {

    private FoodFinanceDtos() {}

    public record RefundView(String id, String orderId, String paymentId, String provider, String originalReference,
                             String providerRefundReference, BigDecimal amount, String currency, String reason,
                             String status, String providerStatus, String failureReason, int attempts,
                             String customerNote, String merchantNote, Instant createdAt, Instant processedAt) {
        public static RefundView of(PaymentRefund r) {
            return new RefundView(r.getId(), r.getOrder().getId(), r.getPayment().getId(), r.getProvider(),
                    r.getOriginalReference(), r.getProviderRefundReference(), r.getAmount(), r.getCurrency(),
                    r.getReason().name(), r.getStatus().name(), r.getProviderStatus(), r.getFailureReason(),
                    r.getAttempts(), r.getCustomerNote(), r.getMerchantNote(), r.getCreatedAt(), r.getProcessedAt());
        }
    }

    public record SettlementView(String id, String orderId, String businessId, String businessName, String paymentId,
                                 String type, String status, String cause, String currency, BigDecimal foodSubtotal,
                                 BigDecimal deliveryAllocation, BigDecimal refundAmount, BigDecimal eligibleRevenue,
                                 BigDecimal commissionRate, BigDecimal commissionAmount, BigDecimal otherFees,
                                 BigDecimal netAmount, BigDecimal pulaFulfilmentLoss, String payoutProvider,
                                 String payoutReference, int payoutAttempts, String failureReason, String holdReason,
                                 Instant createdAt, Instant eligibleAt, Instant paidAt) {
        public static SettlementView of(RestaurantSettlement s) {
            return new SettlementView(s.getId(), s.getOrder().getId(), s.getBusiness().getId(), s.getBusiness().getName(),
                    s.getPayment() == null ? null : s.getPayment().getId(), s.getType().name(), s.getStatus().name(),
                    s.getCause() == null ? null : s.getCause().name(), s.getCurrency(), s.getFoodSubtotal(),
                    s.getDeliveryAllocation(), s.getRefundAmount(), s.getEligibleRevenue(), s.getCommissionRate(),
                    s.getCommissionAmount(), s.getOtherFees(), s.getNetAmount(), s.getPulaFulfilmentLoss(),
                    s.getPayoutProvider(), s.getPayoutReference(), s.getPayoutAttempts(), s.getFailureReason(),
                    s.getHoldReason(), s.getCreatedAt(), s.getEligibleAt(), s.getPaidAt());
        }
    }

    /** PULA's own totals: what it earns, what it owes restaurants, and what failed fulfilment has cost it. */
    public record FinanceSummary(BigDecimal commissionEarned, BigDecimal pulaFulfilmentLoss, BigDecimal pendingPayouts,
                                 BigDecimal paidOut, long pendingPayoutCount, long failedPayoutCount,
                                 long refundsNeedingAttention, long refundsFailed, long refundsInProgress) {}

    public record PartialRefundRequest(BigDecimal amount, String note, String operationKey) {}
    public record HoldRequest(String reason) {}
}
