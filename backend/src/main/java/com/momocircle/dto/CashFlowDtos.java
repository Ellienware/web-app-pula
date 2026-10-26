package com.momocircle.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs for PULA Cash Flow (brief §16-17). This is NOT a full accounting
 * system — the goal is lightweight cash-flow *awareness* for small businesses.
 * All totals are computed backend-side from authoritative PULA transaction
 * data (consumer orders, stock orders) plus optional manually-entered records.
 * Backend never trusts client-supplied totals.
 */
public class CashFlowDtos {

    /**
     * A manually recorded income item (e.g. "received R500 from a catering
     * job that wasn't processed through PULA"). Complements the auto-derived
     * income from PULA Market orders. Scoped to the business, not the user.
     */
    public record RecordIncomeRequest(
            @NotNull @Min(0) BigDecimal amount,
            @NotBlank String currency,
            @NotNull LocalDate receivedDate,
            /** Free-text description, e.g. "Cash sale outside PULA". */
            String description,
            /** Optional category label — brief §17 suggests "stock spending", "delivery", "other". */
            String category
    ) {}

    public record BusinessIncomeEntryResponse(
            String id,
            BigDecimal amount,
            String currency,
            LocalDate receivedDate,
            String description,
            String category,
            /** "PULA_ORDER" for auto-derived, "MANUAL" for user-entered. Never fabricated. */
            String source,
            Instant createdAt
    ) {}

    /**
     * A manually recorded expense item (e.g. "paid R200 for packaging").
     * PULA auto-derives stock-purchasing expenses from StockOrders; this
     * covers anything outside PULA's system. Brief §16 instructs to allow
     * manual recording of legitimate expenses where appropriate.
     */
    public record RecordExpenseRequest(
            @NotNull @Min(0) BigDecimal amount,
            @NotBlank String currency,
            @NotNull LocalDate incurredDate,
            String description,
            /** Matches brief §16 categories: STOCK, DELIVERY, OPERATING, OTHER. */
            @NotBlank String category
    ) {}

    public record BusinessExpenseEntryResponse(
            String id,
            BigDecimal amount,
            String currency,
            LocalDate incurredDate,
            String description,
            String category,
            String source,
            Instant createdAt
    ) {}

    /**
     * The cash-flow summary for a given period — all figures computed
     * server-side from real PULA transaction data + any manual entries.
     * Brief §16 example table layout exactly:
     *   Sales          Rxxx
     *   Stock purchases -Ryyy
     *   Delivery        -Rzzz
     *   Other expenses  -Rwww
     *   Net cash movement Rvvv
     */
    public record BusinessCashFlowSummary(
            /** Period covered (ISO date strings, e.g. "2026-09-01" / "2026-09-30"). */
            String periodFrom,
            String periodTo,
            String currency,

            /** Revenue from completed consumer orders on PULA Market. */
            BigDecimal salesIncome,
            /** Manually-recorded income not in PULA (cash sales, other income). */
            BigDecimal otherIncome,
            /** Total income = salesIncome + otherIncome. */
            BigDecimal totalIncome,

            /** Spending on stock procurement orders (B2B). */
            BigDecimal stockSpend,
            /** Delivery costs extracted from stock orders (snapshot from DeliveryFeeSnapshot). */
            BigDecimal deliverySpend,
            /** Manually recorded operating expenses. */
            BigDecimal operatingExpenses,
            /** Total expenses = stockSpend + deliverySpend + operatingExpenses. */
            BigDecimal totalExpenses,

            /** totalIncome - totalExpenses. */
            BigDecimal netCashMovement,

            /** Number of completed sales orders in the period. */
            int saleCount,
            /** Number of paid stock orders in the period. */
            int stockOrderCount,

            /** true = all seven readiness items are complete (brief §8). Shown alongside cash flow so the owner can see both. */
            boolean profileComplete,

            /** Snapshot of the most recent 10 income entries (auto + manual) for the ledger view. */
            List<BusinessIncomeEntryResponse> recentIncome,
            /** Snapshot of the most recent 10 expense entries (auto + manual) for the ledger view. */
            List<BusinessExpenseEntryResponse> recentExpenses,

            // ---- PULA Food (all zero for businesses with no food orders). Already inside / outside the totals above as noted. ----
            /** Food sales: item totals on completed food orders, excluding delivery fees. Included in salesIncome. */
            BigDecimal foodSales,
            /** Delivery fees the restaurant keeps for its own riders (not PULA Ride). Included in salesIncome. */
            BigDecimal ownDeliveryIncome,
            /** PULA Ride delivery fees customers paid on completed food orders. NOT restaurant income and NOT in salesIncome. */
            BigDecimal pulaRideDeliveryFees,
            /** Of those PULA Ride fees, the 80% that went to drivers. Information only. */
            BigDecimal pulaRideDriverEarnings,
            /** Of those PULA Ride fees, PULA's 20%. Information only. */
            BigDecimal pulaRideFeeIncome,
            /** PULA's commission on completed food orders. A deduction: INCLUDED in totalExpenses. */
            BigDecimal foodCommission,
            /** Merchant-protection compensation for prepared food PULA could not deliver. These orders never completed, so it is NOT in salesIncome; INCLUDED in totalIncome. */
            BigDecimal merchantProtectionIncome,
            /** Customer refunds completed on this restaurant's food orders. Information only: refunded orders are not in salesIncome, so nothing is deducted twice. */
            BigDecimal foodRefunds,
            /** Settlements (sales and compensation) PULA has actually paid out. Information only: the cash realisation of income already counted, so it is NOT added again. */
            BigDecimal foodSettlementsPaid,
            /** Settlements earned but not yet paid out (eligible, processing, failed or on hold). Information only. */
            BigDecimal foodSettlementsOwed
    ) {
        public BusinessCashFlowSummary(String periodFrom, String periodTo, String currency,
                                       BigDecimal salesIncome, BigDecimal otherIncome, BigDecimal totalIncome,
                                       BigDecimal stockSpend, BigDecimal deliverySpend, BigDecimal operatingExpenses,
                                       BigDecimal totalExpenses, BigDecimal netCashMovement, int saleCount,
                                       int stockOrderCount, boolean profileComplete,
                                       List<BusinessIncomeEntryResponse> recentIncome,
                                       List<BusinessExpenseEntryResponse> recentExpenses) {
            this(periodFrom, periodTo, currency, salesIncome, otherIncome, totalIncome, stockSpend, deliverySpend,
                    operatingExpenses, totalExpenses, netCashMovement, saleCount, stockOrderCount, profileComplete,
                    recentIncome, recentExpenses, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
    /** One period in the cash-flow trend (week or month). All figures computed server-side. */
    public record CashFlowTrendPoint(
            String periodFrom, String periodTo,
            BigDecimal totalIncome, BigDecimal totalExpenses, BigDecimal netCashMovement,
            BigDecimal stockSpend, BigDecimal deliverySpend) {}
}
