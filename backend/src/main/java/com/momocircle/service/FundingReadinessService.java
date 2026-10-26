package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.dto.CashFlowDtos.BusinessCashFlowSummary;
import com.momocircle.dto.FundingReadinessDtos.FundingReadinessReport;
import com.momocircle.dto.FundingReadinessDtos.FundingReadinessResponse;
import com.momocircle.dto.FundingReadinessDtos.ReadinessItem;
import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.repository.BusinessDocumentRepository;
import com.momocircle.repository.BusinessExpenseEntryRepository;
import com.momocircle.repository.BusinessIncomeEntryRepository;
import com.momocircle.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PULA Funding Readiness. Builds a yes/no preparation checklist purely from records the
 * business already has on PULA. It deliberately produces NO score, rating, approval
 * likelihood or creditworthiness label — only what is present, what is missing, and what
 * to prepare. Owner-only: every read goes through BusinessService.requireOwn, so one
 * business can never see another's financial records.
 */
@Service
public class FundingReadinessService {

    static final String DISCLAIMER =
            "This is a preparation checklist based on records held on PULA. It is not a credit assessment, "
          + "does not predict whether any funder will approve an application, and PULA does not make lending decisions.";

    private static final int WINDOW_DAYS = 180;

    private final BusinessService businessService;
    private final BusinessReadinessService profileService;
    private final BusinessCashFlowService cashFlowService;
    private final BusinessDocumentRepository documentRepository;
    private final BusinessIncomeEntryRepository incomeRepository;
    private final BusinessExpenseEntryRepository expenseRepository;
    private final OrderRepository orderRepository;

    public FundingReadinessService(BusinessService businessService, BusinessReadinessService profileService,
                                   BusinessCashFlowService cashFlowService,
                                   BusinessDocumentRepository documentRepository,
                                   BusinessIncomeEntryRepository incomeRepository,
                                   BusinessExpenseEntryRepository expenseRepository,
                                   OrderRepository orderRepository) {
        this.businessService = businessService;
        this.profileService = profileService;
        this.cashFlowService = cashFlowService;
        this.documentRepository = documentRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public FundingReadinessResponse readiness(User owner) {
        return build(owner).readiness();
    }

    @Transactional(readOnly = true)
    public FundingReadinessReport report(User owner) {
        return build(owner);
    }

    private FundingReadinessReport build(User owner) {
        Business business = businessService.requireOwn(owner);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(WINDOW_DAYS);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BusinessProfileCompleteness profile = profileService.computeFor(business);
        BusinessCashFlowSummary cf = cashFlowService.summary(owner, from, to);

        var incomeEntries = incomeRepository.findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(business.getId(), from, to);
        var expenseEntries = expenseRepository.findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(business.getId(), from, to);
        var sales = orderRepository.findCompletedByBusinessInPeriod(business.getId(), fromInstant, toInstant);

        Set<YearMonth> months = new HashSet<>();
        sales.forEach(o -> months.add(YearMonth.from(o.getCreatedAt().atZone(ZoneOffset.UTC))));
        incomeEntries.forEach(e -> months.add(YearMonth.from(e.getReceivedDate())));
        expenseEntries.forEach(e -> months.add(YearMonth.from(e.getIncurredDate())));

        int documents = (int) documentRepository.countByBusinessId(business.getId());
        boolean profileComplete = profile.completedCount() == profile.totalCount();
        boolean hasRegistration = profile.registrationDetails();
        long ageDays = ChronoUnit.DAYS.between(business.getCreatedAt(), Instant.now());
        boolean tradingHistory = ageDays >= 30 && (cf.saleCount() > 0 || !incomeEntries.isEmpty());
        boolean salesHistory = cf.saleCount() > 0 || !incomeEntries.isEmpty();
        boolean expenseRecords = !expenseEntries.isEmpty();
        boolean cashFlowHistory = months.size() >= 2;
        boolean supportingDocs = documents > 0;
        boolean financialRecords = salesHistory && (expenseRecords || cf.stockOrderCount() > 0) && cashFlowHistory;

        List<ReadinessItem> items = List.of(
                new ReadinessItem("profile", "Business profile", profileComplete,
                        profile.completedCount() + " of " + profile.totalCount() + " profile fields completed"),
                new ReadinessItem("registration", "Business registration info", hasRegistration,
                        hasRegistration ? "Registration details are on file" : "No registration status or number recorded"),
                new ReadinessItem("trading", "Trading history", tradingHistory,
                        tradingHistory ? "Business has been on PULA for " + ageDays + " days with recorded sales"
                                : "Needs at least 30 days on PULA with recorded sales or income"),
                new ReadinessItem("sales", "Sales history", salesHistory,
                        cf.saleCount() + " completed PULA sale(s) and " + incomeEntries.size() + " other income record(s) in the last " + WINDOW_DAYS + " days"),
                new ReadinessItem("expenses", "Expense records", expenseRecords,
                        expenseEntries.size() + " manually recorded expense(s) in the last " + WINDOW_DAYS + " days"),
                new ReadinessItem("cashflow", "Cash-flow history", cashFlowHistory,
                        months.size() + " month(s) with records in the last " + WINDOW_DAYS + " days (2 or more shows a pattern)"),
                new ReadinessItem("documents", "Supporting documents", supportingDocs,
                        documents + " document(s) on file"),
                new ReadinessItem("financial", "Financial records", financialRecords,
                        financialRecords ? "Sales, costs and more than one month of activity are recorded"
                                : "Needs sales, some cost records (expenses or stock purchases) and 2+ active months"));

        List<String> missing = new ArrayList<>();
        List<String> prep = new ArrayList<>();
        if (!profileComplete) { missing.add("Some business profile fields are empty"); prep.add("Complete your business profile (contact details, location, products)."); }
        if (!hasRegistration) { missing.add("Business registration information"); prep.add("Add your registration status and number if you have one. If you are not registered yet, note it — PULA does not register businesses with CIPC."); }
        if (!salesHistory) { missing.add("Sales history"); prep.add("Make sales through PULA Market, or record other income you receive."); }
        else if (!tradingHistory) { prep.add("Keep trading on PULA — a longer record of activity gives funders more to look at."); }
        if (!expenseRecords) { missing.add("Operating expense records"); prep.add("Your business has sales history, but your operating expense records are incomplete. Recording these expenses can make your financial records more complete."); }
        if (!cashFlowHistory) { missing.add("Cash-flow records across more than one month"); prep.add("Keep recording income and expenses each month."); }
        if (!supportingDocs) { missing.add("Supporting business documents"); prep.add("Upload documents you already have (e.g. ID copy, proof of address, registration certificate, bank statements) — check with the funder what they require."); }

        FundingReadinessResponse readiness = new FundingReadinessResponse(
                business.getId(), business.getName(), from.toString(), to.toString(), items,
                (int) items.stream().filter(ReadinessItem::met).count(), items.size(),
                missing, prep, DISCLAIMER);

        List<String> checklist = new ArrayList<>(prep);
        checklist.add("Ask each funder which documents and records they require — requirements differ.");

        return new FundingReadinessReport(
                Instant.now(), readiness,
                String.valueOf(business.getBusinessType()), business.getLocation(),
                String.valueOf(business.getRegistrationStatus()),
                business.getRegistrationNumber() != null && !business.getRegistrationNumber().isBlank(),
                business.getCapabilities().stream().map(Enum::name).sorted().toList(),
                cf.currency(), cf.salesIncome(), cf.otherIncome(), cf.stockSpend(), cf.deliverySpend(),
                cf.operatingExpenses(), cf.netCashMovement(),
                cf.saleCount(), cf.stockOrderCount(), months.size(), documents, checklist,
                "Figures come from PULA records for the period shown. Manually recorded income and expenses are self-reported "
                        + "and are not verified by PULA. Sales and stock purchases come from completed PULA orders.");
    }
}
