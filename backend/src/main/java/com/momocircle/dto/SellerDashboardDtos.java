package com.momocircle.dto;

import com.momocircle.dto.MarketDtos.ProductResponse;

import java.math.BigDecimal;
import java.util.List;

public final class SellerDashboardDtos {

    private SellerDashboardDtos() {}

    public record DailyRevenuePoint(
            java.time.LocalDate date,
            BigDecimal amount
    ) {}

    public record DeliveryStats(
            long requested,
            long delivered,
            long cancelled
    ) {}

    public record SellerDashboardResponse(
            BigDecimal totalSales,
            BigDecimal todaySales,
            BigDecimal weekSales,
            BigDecimal monthSales,
            long totalOrders,
            long pendingOrders,
            long completedOrders,
            long cancelledOrders,
            List<ProductResponse> topProducts,
            String currency,
            long uniqueCustomers,
            /** Last 14 days, oldest first. Days with no revenue are included with amount 0, not skipped. */
            List<DailyRevenuePoint> dailyRevenue,
            DeliveryStats deliveryStats
    ) {}
}
