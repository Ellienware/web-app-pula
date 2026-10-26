package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessExpenseEntry;
import com.momocircle.domain.BusinessIncomeEntry;
import com.momocircle.domain.Order;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.User;
import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.CashFlowDtos.BusinessExpenseEntryResponse;
import com.momocircle.dto.CashFlowDtos.BusinessIncomeEntryResponse;
import com.momocircle.dto.CashFlowDtos.RecordExpenseRequest;
import com.momocircle.dto.CashFlowDtos.RecordIncomeRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessExpenseEntryRepository;
import com.momocircle.repository.BusinessIncomeEntryRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.StockOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PULA Cash Flow (brief §16-17): lightweight cash-flow awareness for small
 * businesses. NOT a full accounting system.
 *
 * Key principles (from the brief):
 * - Use actual PULA transaction data wherever possible (completed Orders,
 *   paid StockOrders). Only supplement with manually-entered records where
 *   the transaction didn't happen through PULA.
 * - Backend is authoritative — never trust client-supplied totals.
 * - Do not fabricate financial data.
 * - Do not present unsupported financial conclusions as facts.
 * - Use clear language, not complex accounting terminology.
 * - This is a *cash-flow awareness* tool, not bookkeeping software.
 */
@Service
public class BusinessCashFlowService {

    private final BusinessService businessService;
    private final BusinessReadinessService readinessService;
    private final OrderRepository orderRepo;
    private final StockOrderRepository stockOrderRepo;
    private final BusinessIncomeEntryRepository incomeRepo;
    private final BusinessExpenseEntryRepository expenseRepo;
    private final com.momocircle.repository.DeliveryEarningsAllocationRepository allocationRepo;

    private final com.momocircle.repository.RestaurantSettlementRepository settlementRepo;
    private final com.momocircle.repository.PaymentRefundRepository refundRepo;

    public BusinessCashFlowService(BusinessService businessService,
                                    BusinessReadinessService readinessService,
                                    OrderRepository orderRepo,
                                    StockOrderRepository stockOrderRepo,
                                    BusinessIncomeEntryRepository incomeRepo,
                                    BusinessExpenseEntryRepository expenseRepo,
                                    com.momocircle.repository.DeliveryEarningsAllocationRepository allocationRepo,
                                    com.momocircle.repository.RestaurantSettlementRepository settlementRepo,
                                    com.momocircle.repository.PaymentRefundRepository refundRepo) {
        this.settlementRepo = settlementRepo;
        this.refundRepo = refundRepo;
        this.businessService = businessService;
        this.readinessService = readinessService;
        this.orderRepo = orderRepo;
        this.stockOrderRepo = stockOrderRepo;
        this.incomeRepo = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.allocationRepo = allocationRepo;
    }

    /**
     * Returns the cash-flow summary for the given period. If from/to are null
     * defaults to the current calendar month (UTC). All amounts are computed
     * server-side from authoritative PULA records — never from client input.
     */
    @Transactional(readOnly = true)
    public BusinessCashFlowSummary summary(User owner, LocalDate from, LocalDate to) {
        Business business = businessService.requireOwn(owner);
        if (from == null) from = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        if (to == null)   to = from.plusMonths(1).minusDays(1);

        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant   = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String currency = "ZAR";

        // ---- Income ----
        // Auto: completed consumer sales from PULA Market
        // PULA Food: a PULA Ride delivery fee is collected on the order but is not the restaurant's sale,
        // so it is taken out of the sales total. Retail orders have no such fee, so nothing changes for them.
        BigDecimal pulaRideFees = orZero(orderRepo.sumPulaRideDeliveryFeesForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal salesIncome = orZero(orderRepo.sumSalesForBusiness(business.getId(), fromInstant, toInstant))
                .subtract(pulaRideFees);
        BigDecimal foodSales = orZero(orderRepo.sumFoodSalesForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal ownDeliveryIncome = orZero(orderRepo.sumOwnDeliveryFeesForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal rideDriverEarnings = orZero(allocationRepo.sumDriverShareForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal rideFeeIncome = orZero(allocationRepo.sumPulaShareForBusiness(business.getId(), fromInstant, toInstant));
        int saleCount = orderRepo.findCompletedByBusinessInPeriod(business.getId(), fromInstant, toInstant).size();

        // Manual: income recorded outside PULA
        BigDecimal otherIncome = orZero(incomeRepo.sumByBusinessInPeriod(business.getId(), from, to));
        // PULA Food settlements. Each figure comes from exactly one record, referenced by the settlement / refund
        // id, so nothing is counted twice: commission is a deduction, compensation is income from orders that never
        // completed (so not already in sales), refunds and payouts are shown for information only.
        BigDecimal foodCommission = orZero(settlementRepo.sumSaleCommissionForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal merchantProtectionIncome = orZero(settlementRepo.sumCompensationForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal foodRefunds = orZero(refundRepo.sumFoodRefundsForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal foodSettlementsPaid = orZero(settlementRepo.sumPaidForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal foodSettlementsOwed = orZero(settlementRepo.sumOwedForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal totalIncome = salesIncome.add(otherIncome).add(merchantProtectionIncome);

        // ---- Expenses ----
        // Auto: stock procurement from StockOrders (PAID and beyond). The repository total includes each
        // order's delivery fee, so the fee is split out: goods -> stockSpend, fee -> deliverySpend.
        // totalExpenses is unchanged by the split, and nothing is counted twice.
        BigDecimal stockOrderTotal = orZero(stockOrderRepo.sumStockSpendForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal stockDeliveryFees = orZero(stockOrderRepo.sumStockDeliveryFeesForBusiness(business.getId(), fromInstant, toInstant));
        BigDecimal stockSpend = stockOrderTotal.subtract(stockDeliveryFees);
        int stockOrderCount = (int) stockOrderRepo.countPaidStockOrdersForBusiness(business.getId(), fromInstant, toInstant);

        // Delivery = fees on stock orders + delivery costs the owner recorded manually (e.g. paying a driver directly).
        BigDecimal deliverySpend = stockDeliveryFees.add(orZero(expenseRepo.sumByBusinessInPeriodAndCategory(
                business.getId(), from, to, "DELIVERY")));

        // Manual: operating expenses not on PULA
        BigDecimal operatingExpenses = orZero(expenseRepo.sumByBusinessInPeriodAndCategory(
                business.getId(), from, to, "OPERATING"))
                .add(orZero(expenseRepo.sumByBusinessInPeriodAndCategory(business.getId(), from, to, "OTHER")));

        BigDecimal totalExpenses = stockSpend.add(deliverySpend).add(operatingExpenses).add(foodCommission);
        BigDecimal netCashMovement = totalIncome.subtract(totalExpenses);

        // ---- Readiness ----
        var readiness = readinessService.computeFor(business);
        boolean profileComplete = readiness.completedCount() == readiness.totalCount();

        // ---- Recent ledger rows (max 10 each) ----
        List<BusinessIncomeEntryResponse> recentIncome = buildRecentIncome(business.getId(), from, to,
                orderRepo.findCompletedByBusinessInPeriod(business.getId(), fromInstant, toInstant),
                incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(business.getId(), from, to),
                currency);

        List<BusinessExpenseEntryResponse> recentExpenses = buildRecentExpenses(business.getId(), from, to,
                expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(business.getId(), from, to));

        return new BusinessCashFlowSummary(
                from.toString(), to.toString(), currency,
                salesIncome, otherIncome, totalIncome,
                stockSpend, deliverySpend, operatingExpenses, totalExpenses,
                netCashMovement, saleCount, stockOrderCount,
                profileComplete, recentIncome, recentExpenses,
                foodSales, ownDeliveryIncome, pulaRideFees, rideDriverEarnings, rideFeeIncome,
                foodCommission, merchantProtectionIncome, foodRefunds, foodSettlementsPaid, foodSettlementsOwed);
    }

    /**
     * Trend for the last N periods (month or week), oldest first. Every point is computed by the
     * same authoritative summary() so trend totals always agree with the period views.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.momocircle.dto.CashFlowDtos.CashFlowTrendPoint> trend(User owner, String granularity, int periods) {
        int n = Math.max(2, Math.min(periods, 12));
        boolean weekly = "week".equalsIgnoreCase(granularity);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        java.util.List<com.momocircle.dto.CashFlowDtos.CashFlowTrendPoint> out = new java.util.ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate from;
            LocalDate to;
            if (weekly) {
                from = today.with(java.time.DayOfWeek.MONDAY).minusWeeks(i);
                to = from.plusDays(6);
            } else {
                from = today.withDayOfMonth(1).minusMonths(i);
                to = from.plusMonths(1).minusDays(1);
            }
            BusinessCashFlowSummary s = summary(owner, from, to);
            out.add(new com.momocircle.dto.CashFlowDtos.CashFlowTrendPoint(
                    from.toString(), to.toString(), s.totalIncome(), s.totalExpenses(), s.netCashMovement(),
                    s.stockSpend(), s.deliverySpend()));
        }
        return out;
    }

    // ---- Manual income ----

    @Transactional
    public BusinessIncomeEntryResponse recordIncome(User owner, RecordIncomeRequest req) {
        Business business = businessService.requireOwn(owner);
        BusinessIncomeEntry entry = new BusinessIncomeEntry();
        entry.setBusiness(business);
        entry.setAmount(req.amount());
        entry.setCurrency(req.currency());
        entry.setReceivedDate(req.receivedDate());
        entry.setDescription(req.description());
        entry.setCategory(req.category());
        return toIncomeResponse(incomeRepo.save(entry), "MANUAL");
    }

    @Transactional
    public void deleteIncomeEntry(User owner, String entryId) {
        Business business = businessService.requireOwn(owner);
        BusinessIncomeEntry entry = incomeRepo.findByIdAndBusinessId(entryId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Income entry not found"));
        incomeRepo.delete(entry);
    }

    // ---- Manual expenses ----

    @Transactional
    public BusinessExpenseEntryResponse recordExpense(User owner, RecordExpenseRequest req) {
        Business business = businessService.requireOwn(owner);
        BusinessExpenseEntry entry = new BusinessExpenseEntry();
        entry.setBusiness(business);
        entry.setAmount(req.amount());
        entry.setCurrency(req.currency());
        entry.setIncurredDate(req.incurredDate());
        entry.setDescription(req.description());
        entry.setCategory(req.category());
        return toExpenseResponse(expenseRepo.save(entry), "MANUAL");
    }

    @Transactional
    public void deleteExpenseEntry(User owner, String entryId) {
        Business business = businessService.requireOwn(owner);
        BusinessExpenseEntry entry = expenseRepo.findByIdAndBusinessId(entryId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Expense entry not found"));
        expenseRepo.delete(entry);
    }

    // ---- Helpers ----

    private BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private List<BusinessIncomeEntryResponse> buildRecentIncome(String businessId, LocalDate from, LocalDate to,
                                                                   List<Order> completedOrders,
                                                                   List<BusinessIncomeEntry> manualEntries,
                                                                   String currency) {
        List<BusinessIncomeEntryResponse> rows = new ArrayList<>();
        // Auto rows from completed orders
        for (Order o : completedOrders) {
            LocalDate d = o.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            // A PULA Ride delivery fee is not the restaurant's sale (matches the summary totals).
            BigDecimal amount = o.getTotal();
            if (o.isFood() && o.getFulfilmentMethod() == com.momocircle.domain.enums.FulfilmentOption.MOMORIDE_DELIVERY
                    && o.getDeliveryFeeSnapshot() != null) {
                amount = amount.subtract(o.getDeliveryFeeSnapshot());
            }
            rows.add(new BusinessIncomeEntryResponse(
                    o.getId(), amount, o.getCurrency(), d,
                    (o.isFood() ? "Food sale — order " : "Sale — order ") + o.getId().substring(0, 8),
                    "PULA_MARKET_SALE", "PULA_ORDER", o.getCreatedAt()));
        }
        // Manual rows
        for (BusinessIncomeEntry e : manualEntries) {
            rows.add(toIncomeResponse(e, "MANUAL"));
        }
        return rows.stream()
                .sorted(Comparator.comparing(BusinessIncomeEntryResponse::receivedDate).reversed())
                .limit(10)
                .toList();
    }

    private List<BusinessExpenseEntryResponse> buildRecentExpenses(String businessId, LocalDate from, LocalDate to,
                                                                     List<BusinessExpenseEntry> manualEntries) {
        return manualEntries.stream()
                .map(e -> toExpenseResponse(e, "MANUAL"))
                .limit(10)
                .toList();
    }

    private BusinessIncomeEntryResponse toIncomeResponse(BusinessIncomeEntry e, String source) {
        return new BusinessIncomeEntryResponse(e.getId(), e.getAmount(), e.getCurrency(),
                e.getReceivedDate(), e.getDescription(), e.getCategory(), source, e.getCreatedAt());
    }

    private BusinessExpenseEntryResponse toExpenseResponse(BusinessExpenseEntry e, String source) {
        return new BusinessExpenseEntryResponse(e.getId(), e.getAmount(), e.getCurrency(),
                e.getIncurredDate(), e.getDescription(), e.getCategory(), source, e.getCreatedAt());
    }
}
