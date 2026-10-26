package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.FundingReadinessDtos.FundingReadinessResponse;
import com.momocircle.dto.JourneyDtos.BusinessJourney;
import com.momocircle.dto.JourneyDtos.JourneyStage;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.WholesaleProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The PULA business lifecycle as one coherent view: Start -> Build -> Sell -> Get paid -> Manage cash ->
 * Restock -> Deliver -> Build records -> Funding-ready -> Grow. Each stage is "done" only when the
 * business has real records for it. It describes progress; it does not promise success or funding.
 */
@Service
public class BusinessJourneyService {

    private final BusinessService businessService;
    private final BusinessReadinessService readinessService;
    private final BusinessCashFlowService cashFlowService;
    private final FundingReadinessService fundingService;
    private final ProductRepository productRepository;
    private final WholesaleProductRepository wholesaleRepository;
    private final StockOrderRepository stockOrderRepository;

    public BusinessJourneyService(BusinessService businessService, BusinessReadinessService readinessService,
                                  BusinessCashFlowService cashFlowService, FundingReadinessService fundingService,
                                  ProductRepository productRepository, WholesaleProductRepository wholesaleRepository,
                                  StockOrderRepository stockOrderRepository) {
        this.businessService = businessService;
        this.readinessService = readinessService;
        this.cashFlowService = cashFlowService;
        this.fundingService = fundingService;
        this.productRepository = productRepository;
        this.wholesaleRepository = wholesaleRepository;
        this.stockOrderRepository = stockOrderRepository;
    }

    @Transactional(readOnly = true)
    public BusinessJourney journey(User owner) {
        Business b;
        try {
            b = businessService.requireOwn(owner);
        } catch (ApiException e) {
            return new BusinessJourney(false, null, List.of(
                    new JourneyStage("START", "Start", "Create your PULA Business profile.", false, "/market")),
                    0, "START", "Create your business to begin. PULA sets up a digital business profile; it does not register a company with CIPC.");
        }
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(180);
        var profile = readinessService.computeFor(b);
        BusinessCashFlowSummary cf = cashFlowService.summary(owner, from, to);
        FundingReadinessResponse fr = fundingService.readiness(owner);

        long listed = productRepository.countByBusinessId(b.getId()) + wholesaleRepository.countBySupplierBusinessId(b.getId());
        boolean profileDone = profile.completedCount() == profile.totalCount();
        boolean sold = cf.saleCount() > 0;
        boolean recorded = cf.otherIncome().signum() > 0 || cf.operatingExpenses().signum() > 0
                || cf.deliverySpend().signum() > 0 || cf.saleCount() > 0 || cf.stockOrderCount() > 0;
        boolean restocked = cf.stockOrderCount() > 0;
        boolean delivered = stockOrderRepository.countByBuyerBusinessIdAndStatus(b.getId(), StockOrderStatus.DELIVERED) > 0;
        boolean recordsBuilt = fr.items().stream().anyMatch(i -> i.key().equals("financial") && i.met());
        boolean fundingReady = fr.metCount() == fr.totalCount();

        List<JourneyStage> stages = new ArrayList<>(List.of(
                new JourneyStage("START", "Start", "Your PULA Business exists.", true, "/market"),
                new JourneyStage("BUILD", "Build your profile", "Complete your business profile and registration details.", profileDone, "/market/business"),
                new JourneyStage("LIST", "List products", "Add products or services so customers can find you.", listed > 0, "/market/business"),
                new JourneyStage("TRADE", "Trade", "Complete your first sale on PULA Market.", sold, "/market"),
                new JourneyStage("PAID", "Get paid & track cash", "Record income and expenses in PULA Cash Flow.", recorded, "/cashflow"),
                new JourneyStage("RESTOCK", "Restock", "Buy stock from a supplier on PULA Market - Stock.", restocked, "/market/stock"),
                new JourneyStage("DELIVER", "Deliver", "Receive a stock order by delivery or collection.", delivered, "/market/stock/orders"),
                new JourneyStage("RECORDS", "Build records", "Sales, costs and more than one month of activity on record.", recordsBuilt, "/cashflow"),
                new JourneyStage("FUNDING", "Become funding-ready", "Have every preparation item in place.", fundingReady, "/funding")));
        int done = (int) stages.stream().filter(JourneyStage::done).count();
        String next = stages.stream().filter(s -> !s.done()).map(JourneyStage::key).findFirst().orElse("GROW");
        return new BusinessJourney(true, b.getName(), stages, done, next,
                "This shows your progress using records on PULA. It is not a promise of business success or funding.");
    }
}
