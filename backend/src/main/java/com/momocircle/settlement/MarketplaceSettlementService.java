package com.momocircle.settlement;

import com.momocircle.domain.Business;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.SettlementAdjustment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.*;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.MarketplaceSettlementCalculator;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.DriverPayoutRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.repository.SettlementAdjustmentRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.settlement.SettlementDtos.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared marketplace settlement engine. Retail, food, stock and services all settle through this one lifecycle
 * (NOT_ELIGIBLE -> ELIGIBLE -> PROCESSING -> PAID, with FAILED / ON_HOLD / CANCELLED), one calculator, one payout machinery,
 * deterministic idempotency ("settlement:{SOURCE_TYPE}:{sourceId}"), and append-only adjustments. What differs per domain
 * lives in a {@link SettlementPolicy}.
 *
 * Settlement never happens just because payment succeeded: a row is opened at payment, becomes ELIGIBLE only when the
 * domain's completion condition is met (plus its dispute window), and is paid only on provider-confirmed success.
 */
@Service
public class MarketplaceSettlementService {

    /** A settlement can still change (and so accept an adjustment) only while nothing has been sent to the provider. */
    private static final Set<SettlementStatus> ADJUSTABLE =
            EnumSet.of(SettlementStatus.NOT_ELIGIBLE, SettlementStatus.ELIGIBLE, SettlementStatus.ON_HOLD, SettlementStatus.FAILED);
    private static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("10000000.00");

    private final Map<SettlementSourceType, SettlementPolicy> policies = new EnumMap<>(SettlementSourceType.class);
    private final RestaurantSettlementRepository settlements;
    private final SettlementAdjustmentRepository adjustments;
    private final SettlementPayoutService payout;
    private final BusinessRepository businesses;
    private final DriverPayoutRepository driverPayouts;
    private final AuditLogService audit;

    public MarketplaceSettlementService(List<SettlementPolicy> policyList, RestaurantSettlementRepository settlements,
                                        SettlementAdjustmentRepository adjustments, SettlementPayoutService payout,
                                        BusinessRepository businesses, DriverPayoutRepository driverPayouts, AuditLogService audit) {
        policyList.forEach(p -> policies.put(p.sourceType(), p));
        this.settlements = settlements;
        this.adjustments = adjustments;
        this.payout = payout;
        this.businesses = businesses;
        this.driverPayouts = driverPayouts;
        this.audit = audit;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketplaceSettlementService.class);

    /**
     * Hook variants used by the payment/fulfilment code. They run inside the caller's transaction (payment confirmation,
     * order completion), so a settlement problem must NEVER roll back a customer's confirmed payment: failures are logged and
     * swallowed, and reconciliation reports the gap as MISSING_SETTLEMENT. Both are idempotent, so they can simply be retried.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public void openSafely(SettlementSourceType type, String sourceId) {
        try {
            open(type, sourceId);
        } catch (RuntimeException e) {
            log.warn("Could not open {} settlement for {}; reconciliation will flag it", type, sourceId, e);
        }
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public void onEarnedSafely(SettlementSourceType type, String sourceId) {
        try {
            onEarned(type, sourceId);
        } catch (RuntimeException e) {
            log.warn("Could not mark {} settlement {} eligible; reconciliation will flag it", type, sourceId, e);
        }
    }

    /** Deterministic idempotency key. Food rows created before this engine keep their legacy "settlement:{orderId}" key. */
    public static String key(SettlementSourceType type, String sourceId) {
        return "settlement:" + type.name() + ":" + sourceId;
    }

    // ------------------------------------------------------------------ lifecycle (called by the domains)

    /**
     * The customer's payment succeeded. Opens the settlement (NOT_ELIGIBLE) exactly once; calling it again, from a
     * duplicate webhook or a retry, returns the same row. Returns null when the sale is not actually paid.
     */
    @Transactional
    public RestaurantSettlement open(SettlementSourceType type, String sourceId) {
        SettlementPolicy policy = policy(type);
        SettlementFacts facts = policy.facts(sourceId);
        if (!facts.paid() || facts.cancelled()) {
            return null;
        }
        RestaurantSettlement existing = settlements.findBySourceTypeAndSourceId(type, sourceId).orElse(null);
        RestaurantSettlement s = existing != null ? existing : create(policy, facts);
        if (facts.earned()) {
            makeEligible(s, policy, facts);
        }
        return s;
    }

    /** The domain's completion condition was met. Idempotent: an already eligible/paid/held/cancelled row is untouched. */
    @Transactional
    public RestaurantSettlement onEarned(SettlementSourceType type, String sourceId) {
        SettlementPolicy policy = policy(type);
        SettlementFacts facts = policy.facts(sourceId);
        if (!facts.paid() || facts.cancelled() || !facts.earned()) {
            return settlements.findBySourceTypeAndSourceId(type, sourceId).orElse(null);
        }
        RestaurantSettlement s = settlements.findBySourceTypeAndSourceId(type, sourceId).orElseGet(() -> create(policy, facts));
        makeEligible(s, policy, facts);
        return s;
    }

    /**
     * The sale was cancelled. An unpaid settlement becomes CANCELLED with nothing owed. Returns false when the seller was
     * already paid (or is being paid): that is a clawback/dispute for a human, never an automatic reversal.
     */
    @Transactional
    public boolean onCancelled(SettlementSourceType type, String sourceId) {
        RestaurantSettlement s = settlements.findBySourceTypeAndSourceId(type, sourceId).orElse(null);
        if (s == null || s.getStatus() == SettlementStatus.CANCELLED) {
            return true;
        }
        if (!s.getStatus().canTransitionTo(SettlementStatus.CANCELLED)) {
            return false;
        }
        s.setNetAmount(BigDecimal.ZERO.setScale(2));
        s.transitionTo(SettlementStatus.CANCELLED);
        settlements.save(s);
        return true;
    }

    /** Recorded refunds changed: recalculate while the settlement is still open. Frozen rows are never rewritten. */
    @Transactional
    public RestaurantSettlement onRefundChanged(SettlementSourceType type, String sourceId) {
        RestaurantSettlement s = settlements.findBySourceTypeAndSourceId(type, sourceId).orElse(null);
        if (s == null || !ADJUSTABLE.contains(s.getStatus())) {
            return s;
        }
        SettlementPolicy policy = policy(type);
        recalculate(s, policy, policy.facts(sourceId));
        return settlements.save(s);
    }

    // ------------------------------------------------------------------ admin: adjustments, hold, release

    /**
     * Adds an immutable, signed adjustment and recalculates the settlement's net. Only while nothing has been sent to the
     * payout provider. Idempotent per (settlement, key). History is never edited: corrections are new rows.
     */
    @Transactional
    public SettlementView adjust(User admin, String settlementId, AdjustmentKind kind, BigDecimal amount, String reason, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_ADJUSTMENT) > 0) {
            throw ApiException.badRequest("Adjustment amount must be positive and reasonable");
        }
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw ApiException.badRequest("A reason (up to 300 characters) is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("An idempotency key is required");
        }
        RestaurantSettlement s = settlements.findByIdForUpdate(settlementId).orElseThrow(() -> ApiException.notFound("Settlement not found"));
        BigDecimal signed = (kind == AdjustmentKind.CREDIT ? amount : amount.negate()).setScale(2, java.math.RoundingMode.HALF_UP);
        var prior = adjustments.findBySettlementIdAndIdempotencyKey(s.getId(), idempotencyKey.trim());
        if (prior.isPresent()) {
            if (prior.get().getAmount().compareTo(signed) != 0 || prior.get().getKind() != kind) {
                throw ApiException.conflict("That idempotency key was already used for a different adjustment");
            }
            return view(s); // replay
        }
        if (!ADJUSTABLE.contains(s.getStatus())) {
            throw ApiException.conflict("A settlement that is " + s.getStatus() + " can no longer be adjusted");
        }
        SettlementAdjustment a = new SettlementAdjustment();
        a.setSettlement(s);
        a.setKind(kind);
        a.setAmount(signed);
        a.setReason(reason.trim());
        a.setCreatedBy(admin);
        a.setIdempotencyKey(idempotencyKey.trim());
        adjustments.save(a);
        BigDecimal total = adjustments.sumForSettlement(s.getId());
        s.setAdjustmentAmount((total == null ? BigDecimal.ZERO : total).setScale(2, java.math.RoundingMode.HALF_UP));
        if (s.getType() == SettlementType.SALE && s.getSourceId() != null) {
            SettlementPolicy policy = policy(s.getSourceType());
            recalculate(s, policy, policy.facts(s.getSourceId()));
        } else {
            s.setNetAmount(s.getNetAmount().add(signed).max(BigDecimal.ZERO));
        }
        settlements.save(s);
        audit.record(admin, "SETTLEMENT_ADJUSTED", "SETTLEMENT", s.getId(), kind + " " + amount + ": " + reason.trim());
        return view(s);
    }

    @Transactional
    public SettlementView hold(User admin, String settlementId, String reason) {
        RestaurantSettlement s = payout.hold(settlementId, reason);
        audit.record(admin, "SETTLEMENT_HELD", "SETTLEMENT", settlementId, reason == null ? "" : reason);
        return view(s);
    }

    @Transactional
    public SettlementView release(User admin, String settlementId) {
        RestaurantSettlement s = payout.release(settlementId);
        audit.record(admin, "SETTLEMENT_RELEASED", "SETTLEMENT", settlementId, "");
        return view(s);
    }

    // payout driving (scheduler) ------------------------------------------------

    public List<SettlementPayoutService.DuePayout> dueForPayout() {
        return payout.dueForPayout();
    }

    public boolean pay(String settlementId, boolean stuck) {
        return payout.pay(settlementId, stuck);
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public List<SettlementView> adminList(List<SettlementStatus> statuses, SettlementSourceType source, int limit) {
        return payout.list(statuses, source, limit).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SettlementView adminGet(String id) {
        return view(settlements.findById(id).orElseThrow(() -> ApiException.notFound("Settlement not found")));
    }

    /** A seller sees only their own business's settlements; any other id is simply "not found". */
    @Transactional(readOnly = true)
    public List<SettlementView> mine(User owner, int limit) {
        Business b = businesses.findByOwnerId(owner.getId()).orElse(null);
        if (b == null) return List.of();
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return settlements.findByBusinessIdOrderByCreatedAtDesc(b.getId(), page).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SettlementView mineOne(User owner, String id) {
        RestaurantSettlement s = settlements.findById(id).orElseThrow(() -> ApiException.notFound("Settlement not found"));
        if (!s.getBusiness().getOwner().getId().equals(owner.getId())) {
            throw ApiException.notFound("Settlement not found");
        }
        return view(s);
    }

    @Transactional(readOnly = true)
    public SellerSummary sellerSummary(User owner, Instant from, Instant to) {
        Business b = businesses.findByOwnerId(owner.getId()).orElseThrow(() -> ApiException.notFound("No business"));
        List<SourceTotals> rows = new ArrayList<>();
        for (Object[] r : settlements.totalsForBusiness(b.getId(), from, to)) {
            rows.add(new SourceTotals((SettlementSourceType) r[0], (Long) r[1], (BigDecimal) r[2], (BigDecimal) r[3],
                    (BigDecimal) r[4], BigDecimal.ZERO, (BigDecimal) r[5], (BigDecimal) r[6]));
        }
        return new SellerSummary(b.getId(), rows, nz(settlements.sumNetForBusinessAndStatus(b.getId(), SettlementStatus.PAID)),
                nz(settlements.sumNetForBusinessAndStatus(b.getId(), SettlementStatus.ELIGIBLE))
                        .add(nz(settlements.sumNetForBusinessAndStatus(b.getId(), SettlementStatus.PROCESSING))),
                nz(settlements.sumNetForBusinessAndStatus(b.getId(), SettlementStatus.ON_HOLD)));
    }

    /** Admin marketplace finance. Cash Flow reporting only: it reads settlements and driver payouts, it holds no money. */
    @Transactional(readOnly = true)
    public MarketplaceSummary summary() {
        List<SourceTotals> bySource = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO, commission = BigDecimal.ZERO, fees = BigDecimal.ZERO, refunds = BigDecimal.ZERO, net = BigDecimal.ZERO;
        for (Object[] r : settlements.totalsBySource()) {
            SourceTotals t = new SourceTotals((SettlementSourceType) r[0], (Long) r[1], (BigDecimal) r[2], (BigDecimal) r[3],
                    (BigDecimal) r[4], (BigDecimal) r[5], (BigDecimal) r[7], (BigDecimal) r[6]);
            bySource.add(t);
            gross = gross.add(t.gross());
            commission = commission.add(t.commission());
            fees = fees.add(t.paymentFees());
            refunds = refunds.add(t.refunds());
            net = net.add(t.sellerNet());
        }
        List<StatusTotals> byStatus = new ArrayList<>();
        long failed = 0, held = 0;
        for (Object[] r : settlements.totalsByStatus()) {
            SettlementStatus st = (SettlementStatus) r[0];
            byStatus.add(new StatusTotals(st, (Long) r[1], (BigDecimal) r[2]));
            if (st == SettlementStatus.FAILED) failed = (Long) r[1];
            if (st == SettlementStatus.ON_HOLD) held = (Long) r[1];
        }
        List<DriverPayoutTotals> drivers = new ArrayList<>();
        for (Object[] r : driverPayouts.totalsByStatus()) {
            drivers.add(new DriverPayoutTotals((PayoutStatus) r[0], (Long) r[1], (BigDecimal) r[2]));
        }
        return new MarketplaceSummary(gross, commission, fees, refunds, net, nz(settlements.sumPulaLossAllSources()),
                bySource, byStatus, drivers, failed, held);
    }

    // ------------------------------------------------------------------ internals

    SettlementView view(RestaurantSettlement s) {
        List<AdjustmentView> hist = adjustments.findBySettlementIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(a -> new AdjustmentView(a.getId(), a.getKind(), a.getAmount(), a.getReason(),
                        a.getCreatedBy().getFullName(), a.getCreatedAt())).toList();
        boolean comp = s.getType() == SettlementType.MERCHANT_PROTECTION;
        return new SettlementView(s.getId(), s.getSourceType(), s.getSourceId(), s.getSellerKind(), s.getBusiness().getId(),
                s.getBusiness().getName(), s.getType(), s.getStatus(), s.getCurrency(), s.getGrossAmount(), s.getRefundAmount(),
                s.getEligibleRevenue(), s.getCommissionRate(), s.getCommissionAmount(), s.getOtherFees(), s.getPaymentFeeAmount(),
                s.getAdjustmentAmount(), s.getNetAmount(), comp ? s.getNetAmount() : BigDecimal.ZERO,
                nz(s.getPulaFulfilmentLoss()), s.getEligibleAt(), s.getPaidAt(), s.getPayoutProvider(), s.getPayoutReference(),
                s.getHoldReason(), s.getFailureReason(), s.getPayoutAttempts(), s.getCreatedAt(), hist);
    }

    private RestaurantSettlement create(SettlementPolicy policy, SettlementFacts f) {
        RestaurantSettlement s = new RestaurantSettlement();
        s.setSourceType(f.sourceType());
        s.setSourceId(f.sourceId());
        s.setSellerKind(f.sellerKind());
        s.setOrder(f.order());
        s.setBusiness(f.seller());
        s.setPayment(f.payment());
        s.setCurrency(f.currency());
        s.setIdempotencyKey(f.sourceType() == SettlementSourceType.FOOD_ORDER ? "settlement:" + f.sourceId() : key(f.sourceType(), f.sourceId()));
        recalculate(s, policy, f);
        try {
            return settlements.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            // Lost a creation race (duplicate webhook): the unique (source_type, source_id) means someone else just made it.
            return settlements.findBySourceTypeAndSourceId(f.sourceType(), f.sourceId()).orElseThrow(() -> e);
        }
    }

    private void makeEligible(RestaurantSettlement s, SettlementPolicy policy, SettlementFacts f) {
        if (s.getType() != SettlementType.SALE || s.getStatus() != SettlementStatus.NOT_ELIGIBLE) {
            return;
        }
        recalculate(s, policy, f);
        s.transitionTo(SettlementStatus.ELIGIBLE);
        s.setEligibleAt(Instant.now().plus(Duration.ofMinutes(Math.max(0, policy.disputeWindowMinutes()))));
        settlements.save(s);
    }

    private void recalculate(RestaurantSettlement s, SettlementPolicy policy, SettlementFacts f) {
        BigDecimal refunded = nz(policy.refunded(f));
        MarketplaceSettlementCalculator.Result r = MarketplaceSettlementCalculator.calculate(
                nz(f.gross()), refunded, s.getAdjustmentAmount(), policy.rates());
        s.setGrossAmount(r.gross());
        s.setFoodSubtotal(r.gross());               // legacy column name; for non-food sources it simply holds the gross
        s.setDeliveryAllocation(BigDecimal.ZERO.setScale(2));
        s.setRefundAmount(r.refunded());
        s.setEligibleRevenue(r.eligible());
        s.setCommissionRate(r.commissionRate());
        s.setCommissionAmount(r.commission());
        s.setOtherFees(r.fixedFee());
        s.setPaymentFeeAmount(r.paymentFee());
        s.setAdjustmentAmount(r.adjustments());
        s.setNetAmount(r.net());
    }

    private SettlementPolicy policy(SettlementSourceType type) {
        SettlementPolicy p = policies.get(type);
        if (p == null) throw ApiException.badRequest("No settlement policy for " + type);
        return p;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
