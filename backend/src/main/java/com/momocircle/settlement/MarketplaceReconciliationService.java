package com.momocircle.settlement;

import com.momocircle.domain.Payment;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.ReconciliationState;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.repository.ServiceBookingRepository;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.settlement.SettlementDtos.ReconciliationReport;
import com.momocircle.settlement.SettlementDtos.ReconciliationRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only reconciliation of sale -> payment -> settlement -> payout across all marketplace domains. It reports; it never
 * changes a record, so there is no way to "override" a reconciliation through it. Each problem row carries the figures and
 * references an administrator needs to investigate.
 */
@Service
public class MarketplaceReconciliationService {

    private final Map<SettlementSourceType, SettlementPolicy> policies = new EnumMap<>(SettlementSourceType.class);
    private final OrderRepository orders;
    private final StockOrderRepository stockOrders;
    private final ServiceBookingRepository bookings;
    private final RestaurantSettlementRepository settlements;

    public MarketplaceReconciliationService(List<SettlementPolicy> policyList, OrderRepository orders, StockOrderRepository stockOrders,
                                            ServiceBookingRepository bookings, RestaurantSettlementRepository settlements) {
        policyList.forEach(p -> policies.put(p.sourceType(), p));
        this.orders = orders;
        this.stockOrders = stockOrders;
        this.bookings = bookings;
        this.settlements = settlements;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(int limitPerSource) {
        int n = Math.max(1, Math.min(limitPerSource, 500));
        var page = PageRequest.of(0, n);
        List<ReconciliationRow> rows = new ArrayList<>();
        long[] counts = new long[2]; // checked, matched
        Set<String> seen = new HashSet<>();

        // Pass 1: every paid sale must have a consistent payment and (once earned) a consistent settlement.
        List<String[]> sales = new ArrayList<>();
        orders.findRecentPaid(page).forEach(o -> sales.add(new String[] {(o.isFood() ? SettlementSourceType.FOOD_ORDER : SettlementSourceType.RETAIL_ORDER).name(), o.getId()}));
        stockOrders.findRecentPaid(page).forEach(o -> sales.add(new String[] {SettlementSourceType.STOCK_ORDER.name(), o.getId()}));
        bookings.findRecentPaid(page).forEach(b -> sales.add(new String[] {SettlementSourceType.SERVICE_BOOKING.name(), b.getId()}));
        for (String[] sale : sales) {
            SettlementSourceType type = SettlementSourceType.valueOf(sale[0]);
            seen.add(type + ":" + sale[1]);
            check(type, sale[1], rows, counts);
        }
        // Pass 2: every recent settlement must point at a paid sale (catches MISSING_PAYMENT).
        for (RestaurantSettlement s : settlements.findAllByOrderByCreatedAtDesc(PageRequest.of(0, n * 4))) {
            if (s.getSourceId() == null || seen.contains(s.getSourceType() + ":" + s.getSourceId())) continue;
            check(s.getSourceType(), s.getSourceId(), rows, counts);
        }
        return new ReconciliationReport(Instant.now(), counts[0], counts[1], counts[0] - counts[1], rows);
    }

    /** Reconciles one sale (used by the admin "investigate" action and by the tests). */
    @Transactional(readOnly = true)
    public ReconciliationRow reconcileOne(SettlementSourceType type, String sourceId) {
        List<ReconciliationRow> rows = new ArrayList<>();
        check(type, sourceId, rows, new long[2]);
        if (rows.isEmpty()) {
            return new ReconciliationRow(type, sourceId, ReconciliationState.MATCHED, "All records agree", null, null, null, null, null, null);
        }
        return rows.get(0);
    }

    private void check(SettlementSourceType type, String sourceId, List<ReconciliationRow> rows, long[] counts) {
        counts[0]++;
        SettlementPolicy policy = policies.get(type);
        if (policy == null) {
            throw ApiException.badRequest("No settlement policy for " + type);
        }
        SettlementFacts f;
        try {
            f = policy.facts(sourceId);
        } catch (ApiException e) {
            rows.add(row(type, sourceId, ReconciliationState.MISMATCH, "The sale behind this settlement no longer exists", null, null, null, null, null, null));
            return;
        }
        RestaurantSettlement s = settlements.findBySourceTypeAndSourceId(type, sourceId).orElse(null);
        Payment p = f.payment();
        String payRef = p == null ? null : p.getProviderReference();
        String settlementId = s == null ? null : s.getId();
        String payoutRef = s == null ? null : s.getPayoutReference();
        BigDecimal payAmount = p == null ? null : p.getAmount();
        BigDecimal settlementGross = s == null ? null : s.getGrossAmount();

        ReconciliationState state = ReconciliationState.MATCHED;
        String detail = "All records agree";

        if (s != null && !f.paid()) {
            state = ReconciliationState.MISSING_PAYMENT;
            detail = "A settlement exists but the customer's payment is " + (p == null ? "missing" : "not confirmed");
        } else if (p != null && f.sourceTotal() != null && payAmount != null && payAmount.compareTo(f.sourceTotal()) != 0) {
            state = ReconciliationState.AMOUNT_MISMATCH;
            detail = "Payment amount " + payAmount + " differs from the sale total " + f.sourceTotal();
        } else if (p != null && f.currency() != null && p.getCurrency() != null && !p.getCurrency().equals(f.currency())) {
            state = ReconciliationState.REFERENCE_MISMATCH;
            detail = "Payment currency " + p.getCurrency() + " differs from the sale currency " + f.currency();
        } else if (s == null) {
            if (f.paid() && f.earned() && !f.cancelled()) {
                state = ReconciliationState.MISSING_SETTLEMENT;
                detail = "The sale is paid and complete but has no settlement";
            }
        } else {
            if (f.gross() != null && settlementGross != null && settlementGross.compareTo(f.gross()) != 0) {
                state = ReconciliationState.AMOUNT_MISMATCH;
                detail = "Settlement gross " + settlementGross + " differs from the sale's gross " + f.gross();
            } else if (p != null && s.getPayment() != null && !s.getPayment().getId().equals(p.getId())) {
                state = ReconciliationState.REFERENCE_MISMATCH;
                detail = "The settlement points at a different payment than the sale";
            } else if (s.getStatus() == SettlementStatus.PAID && s.getNetAmount().signum() > 0
                    && (payoutRef == null || payoutRef.isBlank())) {
                state = ReconciliationState.MISSING_PAYOUT;
                detail = "Marked PAID for " + s.getNetAmount() + " but there is no payout reference";
            } else if (f.cancelled() && s.getStatus() != SettlementStatus.CANCELLED && s.getStatus() != SettlementStatus.PAID) {
                state = ReconciliationState.MISMATCH;
                detail = "The sale was cancelled but its settlement is still " + s.getStatus();
            } else if (s.getStatus() == SettlementStatus.PAID && f.cancelled()) {
                state = ReconciliationState.MISMATCH;
                detail = "The seller was paid but the sale was later cancelled: needs a human decision";
            }
        }
        if (state == ReconciliationState.MATCHED) {
            counts[1]++;
            return;
        }
        rows.add(row(type, sourceId, state, detail, f.sourceTotal(), payAmount, settlementGross, payRef, payoutRef, settlementId));
    }

    private static ReconciliationRow row(SettlementSourceType t, String id, ReconciliationState st, String d, BigDecimal total,
                                         BigDecimal pay, BigDecimal gross, String payRef, String payoutRef, String settlementId) {
        return new ReconciliationRow(t, id, st, d, total, pay, gross, payRef, payoutRef, settlementId);
    }
}
