package com.momocircle.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Funding Readiness is a preparation checklist — NOT a lending decision, credit score or approval prediction. */
public class FundingReadinessDtos {

    public record ReadinessItem(String key, String label, boolean met, String detail) {}

    public record FundingReadinessResponse(
            String businessId,
            String businessName,
            String periodFrom,
            String periodTo,
            List<ReadinessItem> items,
            int metCount,
            int totalCount,
            List<String> missingInformation,
            List<String> recommendedPreparation,
            String disclaimer) {}

    public record FundingReadinessReport(
            Instant generatedAt,
            FundingReadinessResponse readiness,
            String businessType,
            String location,
            String registrationStatus,
            boolean registrationNumberProvided,
            List<String> capabilities,
            String currency,
            BigDecimal recordedSales,
            BigDecimal otherRecordedIncome,
            BigDecimal stockPurchases,
            BigDecimal deliveryCosts,
            BigDecimal operatingAndOtherExpenses,
            BigDecimal netCashMovement,
            int completedSales,
            int stockOrders,
            int monthsWithRecords,
            int documentsOnFile,
            List<String> preparationChecklist,
            String dataNote) {}
}
