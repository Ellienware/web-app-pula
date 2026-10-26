package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.FundingReadinessDtos.FundingReadinessResponse;
import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NOT VERIFIED IN THIS SANDBOX — no Maven network access. Run ./mvnw test locally. */
class FundingReadinessServiceTest {

    private FundingReadinessResponse run(BusinessProfileCompleteness profile, int saleCount, int stockOrders,
                                         int docs, boolean newBusiness) {
        User owner = new User();
        Business b = new Business();
        b.setOwner(owner);
        b.setName("Thandi's Spaza");
        b.setCreatedAt(Instant.now().minus(newBusiness ? 2 : 90, ChronoUnit.DAYS));
        BusinessService bs = mock(BusinessService.class);
        when(bs.requireOwn(owner)).thenReturn(b);
        BusinessReadinessService rs = mock(BusinessReadinessService.class);
        when(rs.computeFor(b)).thenReturn(profile);
        BusinessCashFlowService cf = mock(BusinessCashFlowService.class);
        BusinessCashFlowSummary sum = mock(BusinessCashFlowSummary.class);
        when(sum.saleCount()).thenReturn(saleCount);
        when(sum.stockOrderCount()).thenReturn(stockOrders);
        when(sum.currency()).thenReturn("ZAR");
        when(sum.salesIncome()).thenReturn(BigDecimal.ZERO);
        when(sum.otherIncome()).thenReturn(BigDecimal.ZERO);
        when(sum.stockSpend()).thenReturn(BigDecimal.ZERO);
        when(sum.deliverySpend()).thenReturn(BigDecimal.ZERO);
        when(sum.operatingExpenses()).thenReturn(BigDecimal.ZERO);
        when(sum.netCashMovement()).thenReturn(BigDecimal.ZERO);
        when(cf.summary(any(), any(), any())).thenReturn(sum);
        BusinessDocumentRepository docsRepo = mock(BusinessDocumentRepository.class);
        when(docsRepo.countByBusinessId(b.getId())).thenReturn((long) docs);
        BusinessIncomeEntryRepository inc = mock(BusinessIncomeEntryRepository.class);
        BusinessExpenseEntryRepository exp = mock(BusinessExpenseEntryRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(inc.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(any(), any(), any())).thenReturn(List.of());
        when(exp.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(any(), any(), any())).thenReturn(List.of());
        when(orders.findCompletedByBusinessInPeriod(any(), any(), any())).thenReturn(List.of());
        return new FundingReadinessService(bs, rs, cf, docsRepo, inc, exp, orders).readiness(owner);
    }

    @Test
    void emptyBusinessListsMissingInformationAndNeverScoresOrApproves() {
        var r = run(new BusinessProfileCompleteness(true, true, false, true, false, false, false, 3, 7), 0, 0, 0, true);

        assertEquals(8, r.totalCount());
        assertTrue(r.metCount() < r.totalCount());
        assertFalse(r.missingInformation().isEmpty());
        assertFalse(r.recommendedPreparation().isEmpty());
        assertTrue(r.disclaimer().contains("does not predict"));
        String all = (r.missingInformation() + " " + r.recommendedPreparation() + " " + r.disclaimer()).toLowerCase();
        assertFalse(all.contains("you will qualify"));
        assertFalse(all.contains("approved"));
    }

    @Test
    void salesWithoutExpenseRecordsFlagsOnlyTheExpenseGap() {
        var r = run(new BusinessProfileCompleteness(true, true, true, true, true, true, true, 7, 7), 5, 0, 1, false);

        assertTrue(r.items().stream().filter(i -> i.key().equals("sales")).findFirst().orElseThrow().met());
        assertFalse(r.items().stream().filter(i -> i.key().equals("expenses")).findFirst().orElseThrow().met());
        assertTrue(r.recommendedPreparation().stream().anyMatch(s -> s.contains("operating expense records")));
    }
}
