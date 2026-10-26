package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessExpenseEntry;
import com.momocircle.domain.BusinessIncomeEntry;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.CashFlowDtos.RecordExpenseRequest;
import com.momocircle.dto.CashFlowDtos.RecordIncomeRequest;
import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.repository.BusinessExpenseEntryRepository;
import com.momocircle.repository.BusinessIncomeEntryRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.StockOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven network access.
 * Run ./mvnw test locally before merging.
 */
class BusinessCashFlowServiceTest {

    private BusinessService businessService;
    private BusinessReadinessService readinessService;
    private OrderRepository orderRepo;
    private StockOrderRepository stockOrderRepo;
    private BusinessIncomeEntryRepository incomeRepo;
    private BusinessExpenseEntryRepository expenseRepo;
    private com.momocircle.repository.DeliveryEarningsAllocationRepository allocationRepo;
    private BusinessCashFlowService service;

    private User owner;
    private Business business;

    @BeforeEach
    void setUp() {
        businessService = mock(BusinessService.class);
        readinessService = mock(BusinessReadinessService.class);
        orderRepo = mock(OrderRepository.class);
        stockOrderRepo = mock(StockOrderRepository.class);
        incomeRepo = mock(BusinessIncomeEntryRepository.class);
        expenseRepo = mock(BusinessExpenseEntryRepository.class);
        allocationRepo = mock(com.momocircle.repository.DeliveryEarningsAllocationRepository.class);
        service = new BusinessCashFlowService(businessService, readinessService,
                orderRepo, stockOrderRepo, incomeRepo, expenseRepo,
                allocationRepo, mock(com.momocircle.repository.RestaurantSettlementRepository.class),
                mock(com.momocircle.repository.PaymentRefundRepository.class));

        owner = new User();
        owner.setId("owner-1");
        business = new Business();
        business.setOwner(owner);
        business.setName("Thandi's Spaza");
        business.setCategory("Grocery");
        business.setCapabilities(new java.util.LinkedHashSet<>(
                List.of(BusinessCapability.CAN_SELL_TO_CONSUMERS, BusinessCapability.CAN_BUY_STOCK)));

        when(businessService.requireOwn(owner)).thenReturn(business);
        when(readinessService.computeFor(business)).thenReturn(
                new BusinessProfileCompleteness(true, true, true, true, true, false, false, 5, 7));
    }

    @Test
    void summaryComputesTotalsFromRealData() {
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("12500.00"));
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(new BigDecimal("500.00"));
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("6000.00"));
        when(stockOrderRepo.countPaidStockOrdersForBusiness(anyString(), any(), any())).thenReturn(3L);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        BusinessCashFlowSummary summary = service.summary(owner,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        // totalIncome = 12500 + 500 = 13000
        assertEquals(0, new BigDecimal("13000.00").compareTo(summary.totalIncome()));
        // totalExpenses = 6000 stock + 0 delivery + 0 operating
        assertEquals(0, new BigDecimal("6000.00").compareTo(summary.totalExpenses()));
        // netCashMovement = 13000 - 6000 = 7000
        assertEquals(0, new BigDecimal("7000.00").compareTo(summary.netCashMovement()));
        assertEquals(3, summary.stockOrderCount());
    }

    @Test
    void pulaRideDeliveryFeeIsNotCountedAsRestaurantSalesButIsReportedSeparately() {
        // Completed orders total R190: R150 food + R20 own-rider fee + R20 PULA Ride fee.
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("190.00"));
        when(orderRepo.sumPulaRideDeliveryFeesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("20.00"));
        when(orderRepo.sumFoodSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("150.00"));
        when(orderRepo.sumOwnDeliveryFeesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("20.00"));
        when(allocationRepo.sumDriverShareForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("16.00"));
        when(allocationRepo.sumPulaShareForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("4.00"));
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        BusinessCashFlowSummary s = service.summary(owner, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(0, new BigDecimal("170.00").compareTo(s.salesIncome()));   // 190 - 20 PULA Ride fee
        assertEquals(0, new BigDecimal("170.00").compareTo(s.totalIncome()));
        assertEquals(0, new BigDecimal("150.00").compareTo(s.foodSales()));
        assertEquals(0, new BigDecimal("20.00").compareTo(s.ownDeliveryIncome()));
        assertEquals(0, new BigDecimal("20.00").compareTo(s.pulaRideDeliveryFees()));
        assertEquals(0, new BigDecimal("16.00").compareTo(s.pulaRideDriverEarnings()));
        assertEquals(0, new BigDecimal("4.00").compareTo(s.pulaRideFeeIncome()));
        assertEquals(0, new BigDecimal("170.00").compareTo(s.netCashMovement()));
    }

    @Test
    void retailOnlyBusinessSeesNoFoodFiguresAndUnchangedTotals() {
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("300.00"));
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        BusinessCashFlowSummary s = service.summary(owner, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(0, new BigDecimal("300.00").compareTo(s.salesIncome()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.pulaRideDeliveryFees()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.foodSales()));
    }

    @Test
    void summaryDefaultsToCurrentMonthWhenNoDatesProvided() {
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.countPaidStockOrdersForBusiness(anyString(), any(), any())).thenReturn(0L);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        // No from/to supplied
        BusinessCashFlowSummary summary = service.summary(owner, null, null);

        assertNotNull(summary.periodFrom());
        assertNotNull(summary.periodTo());
        // Should have defaulted to current month (day 1)
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), summary.periodFrom());
    }

    @Test
    void summaryHandlesNullFromDatabaseGracefully() {
        // Some aggregation functions return null when there are no rows to sum
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(null);
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(null);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(null);
        when(stockOrderRepo.countPaidStockOrdersForBusiness(anyString(), any(), any())).thenReturn(0L);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(null);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        BusinessCashFlowSummary summary = service.summary(owner,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        // Should not throw, and net should be zero
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.netCashMovement()));
    }

    @Test
    void recordIncomeCreatesManualEntryForBusiness() {
        when(incomeRepo.save(any(BusinessIncomeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        RecordIncomeRequest req = new RecordIncomeRequest(
                new BigDecimal("350.00"), "ZAR",
                LocalDate.of(2026, 9, 15), "Cash sale from tuck shop", "CASH_SALE");

        var response = service.recordIncome(owner, req);

        assertEquals(0, new BigDecimal("350.00").compareTo(response.amount()));
        assertEquals("MANUAL", response.source());
    }

    @Test
    void recordExpenseCreatesManualEntryForBusiness() {
        when(expenseRepo.save(any(BusinessExpenseEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        RecordExpenseRequest req = new RecordExpenseRequest(
                new BigDecimal("120.00"), "ZAR",
                LocalDate.of(2026, 9, 10), "Plastic bags and packaging", "OPERATING");

        var response = service.recordExpense(owner, req);

        assertEquals(0, new BigDecimal("120.00").compareTo(response.amount()));
        assertEquals("MANUAL", response.source());
        assertEquals("OPERATING", response.category());
    }
    @Test
    void stockDeliveryFeesAreSplitOutOfStockSpendWithoutChangingTotalExpenses() {
        when(businessService.requireOwn(owner)).thenReturn(business);
        when(readinessService.computeFor(business)).thenReturn(
                new com.momocircle.dto.MarketDtos.BusinessProfileCompleteness(true, true, true, true, true, true, true, 7, 7));
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("6000.00")); // includes fees
        when(stockOrderRepo.sumStockDeliveryFeesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("500.00"));
        when(stockOrderRepo.countPaidStockOrdersForBusiness(anyString(), any(), any())).thenReturn(2L);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());

        var s = service.summary(owner, java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30));

        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("5500.00").compareTo(s.stockSpend()));
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("500.00").compareTo(s.deliverySpend()));
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("6000.00").compareTo(s.totalExpenses()));
    }

    @Test
    void foodSettlementLinesAreRecordedOnceCommissionIsAnExpenseCompensationIsIncomeAndRefundsAndPayoutsAreInformationOnly() {
        var settlementRepo = mock(com.momocircle.repository.RestaurantSettlementRepository.class);
        var refundRepo = mock(com.momocircle.repository.PaymentRefundRepository.class);
        BusinessCashFlowService withFood = new BusinessCashFlowService(businessService, readinessService,
                orderRepo, stockOrderRepo, incomeRepo, expenseRepo, allocationRepo, settlementRepo, refundRepo);
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("12500.00"));
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(new BigDecimal("500.00"));
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("6000.00"));
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(settlementRepo.sumSaleCommissionForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("10.00"));
        when(settlementRepo.sumCompensationForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(settlementRepo.sumPaidForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("90.00"));
        when(settlementRepo.sumOwedForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(refundRepo.sumFoodRefundsForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("120.00"));

        BusinessCashFlowSummary s = withFood.summary(owner, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(0, new BigDecimal("100.00").compareTo(s.merchantProtectionIncome()));
        assertEquals(0, new BigDecimal("10.00").compareTo(s.foodCommission()));
        // income = 12500 sales + 500 other + 100 compensation (an order that never completed, so not in sales)
        assertEquals(0, new BigDecimal("13100.00").compareTo(s.totalIncome()));
        // expenses = 6000 stock + 10 commission
        assertEquals(0, new BigDecimal("6010.00").compareTo(s.totalExpenses()));
        assertEquals(0, new BigDecimal("7090.00").compareTo(s.netCashMovement()));
        // Refunds and payouts are shown but NEVER added to the totals: refunded orders are not in sales, and a payout
        // is only the cash realisation of income already counted.
        assertEquals(0, new BigDecimal("120.00").compareTo(s.foodRefunds()));
        assertEquals(0, new BigDecimal("90.00").compareTo(s.foodSettlementsPaid()));
        assertEquals(0, new BigDecimal("100.00").compareTo(s.foodSettlementsOwed()));
    }

    @Test
    void aBusinessWithNoFoodSettlementsHasZeroFoodLinesAndUnchangedTotals() {
        when(orderRepo.sumSalesForBusiness(anyString(), any(), any())).thenReturn(new BigDecimal("1000.00"));
        when(orderRepo.findCompletedByBusinessInPeriod(anyString(), any(), any())).thenReturn(List.of());
        when(incomeRepo.sumByBusinessInPeriod(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepo.sumStockSpendForBusiness(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenseRepo.sumByBusinessInPeriodAndCategory(anyString(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(incomeRepo.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(anyString(), any(), any())).thenReturn(List.of());
        when(expenseRepo.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(anyString(), any(), any())).thenReturn(List.of());
        BusinessCashFlowSummary s = service.summary(owner, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.foodCommission()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.merchantProtectionIncome()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(s.totalIncome()));
    }
}
