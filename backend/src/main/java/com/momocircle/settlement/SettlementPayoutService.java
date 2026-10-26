package com.momocircle.settlement;

import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.domain.enums.SettlementType;
import com.momocircle.exception.ApiException;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.service.NotificationService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The single payout / hold / release / listing machinery for EVERY settlement source (extracted from the Food
 * settlement service, behaviour unchanged). It reuses the existing PayoutOrchestrator and payout provider; there is no
 * second payout system. PAID is reached only when the provider confirms success; the atomic claim and the stable
 * provider idempotency key ("payout:settlement:{id}") make a double payout impossible. There is no manual PAID.
 *
 * Plain class on purpose: FoodSettlementService builds its own instance (its constructor is unchanged) and a Spring
 * bean is created for the other sources in {@link MarketplaceSettlementConfig}.
 */
public class SettlementPayoutService {

    public record DuePayout(String settlementId, boolean stuck) {}

    /** payout_provider value of a settlement closed against an existing SIMULATED wallet credit (no payout was made). */
    public static final String WALLET_LEDGER_PROVIDER = com.momocircle.finance.FinancialRecordReconciler.WALLET_LEDGER_PROVIDER;

    private final RestaurantSettlementRepository settlements;
    private final PayoutOrchestrator payoutOrchestrator;
    private final NotificationService notifications;
    private final int maxPayoutAttempts;
    private final int stuckProcessingMinutes;
    /** True when the sale behind a settlement has met its domain's "earned" condition (used when a hold is released). */
    private final Predicate<RestaurantSettlement> earned;

    public SettlementPayoutService(RestaurantSettlementRepository settlements, PayoutOrchestrator payoutOrchestrator,
                                   NotificationService notifications, int maxPayoutAttempts, int stuckProcessingMinutes,
                                   Predicate<RestaurantSettlement> earned) {
        this.settlements = settlements;
        this.payoutOrchestrator = payoutOrchestrator;
        this.notifications = notifications;
        this.maxPayoutAttempts = maxPayoutAttempts;
        this.stuckProcessingMinutes = stuckProcessingMinutes;
        this.earned = earned;
    }

    /**
     * What the scheduler should pay now: due ELIGIBLE settlements, FAILED ones under the attempt ceiling, and PROCESSING
     * ones stuck too long. The caller invokes {@link #pay} per item through the proxy (one transaction each) so a failure
     * on one never blocks or rolls back the others.
     */
    @Transactional(readOnly = true)
    public List<DuePayout> dueForPayout() {
        Instant now = Instant.now();
        List<DuePayout> out = new ArrayList<>();
        for (RestaurantSettlement s : settlements.findPayable(now)) out.add(new DuePayout(s.getId(), false));
        for (RestaurantSettlement s : settlements.findRetryable(maxPayoutAttempts)) out.add(new DuePayout(s.getId(), false));
        Instant stuckBefore = now.minus(Duration.ofMinutes(Math.max(1, stuckProcessingMinutes)));
        for (RestaurantSettlement s : settlements.findStuckProcessing(stuckBefore)) out.add(new DuePayout(s.getId(), true));
        return out;
    }

    /** Sends one settlement to the payout provider. Returns true only when the provider confirmed success (PAID). */
    @Transactional
    public boolean pay(String settlementId, boolean stuck) {
        Instant now = Instant.now();
        int claimed = stuck
                ? settlements.claimStuck(settlementId, now, now.minus(Duration.ofMinutes(Math.max(1, stuckProcessingMinutes))))
                : settlements.claimForPayout(settlementId, now);
        if (claimed != 1) return false; // someone else has it, or it is not payable (held, cancelled, paid, not yet eligible)

        RestaurantSettlement s = settlements.findById(settlementId).orElseThrow();
        if (s.getNetAmount().signum() <= 0) {
            // Nothing to send (fully refunded / fees absorbed it): close it without a provider call.
            s.setFailureReason(null);
            s.transitionTo(SettlementStatus.PAID);
            s.setPaidAt(now);
            s.setPayoutProvider("none");
            settlements.save(s);
            return true;
        }
        if (s.getPayment() != null && com.momocircle.payment.PaymentRails.isWallet(s.getPayment())) {
            // A SIMULATED wallet sale already credited the seller's simulated business wallet at payment time (the ledger
            // transaction is on the payment). Sending a payout as well would show the same sale as income twice, so the
            // settlement is closed as an accounting record only: no payout provider call, no second credit.
            s.setFailureReason(null);
            s.transitionTo(SettlementStatus.PAID);
            s.setPaidAt(now);
            s.setPayoutProvider(WALLET_LEDGER_PROVIDER);
            s.setPayoutReference(s.getPayment().getLedgerTransactionId());
            settlements.save(s);
            return true;
        }
        PayoutProvider provider = payoutOrchestrator.providerFor(PayoutOrchestrator.Purpose.RESTAURANT_SETTLEMENT);
        s.setPayoutProvider(provider.name());
        try {
            PayoutProvider.PayoutResult result = provider.pay(new PayoutProvider.PayoutRequest(
                    "payout:settlement:" + s.getId(), s.getNetAmount(), s.getCurrency(),
                    s.getBusiness().getOwner().getPhoneNumber(), description(s)));
            s.setPayoutReference(result.providerReference());
            if (result.status() == PayoutStatus.SUCCESSFUL) {
                s.setFailureReason(null);
                s.transitionTo(SettlementStatus.PAID);
                s.setPaidAt(Instant.now());
                settlements.save(s);
                notifications.notify(s.getBusiness().getOwner(), NotificationType.SYSTEM,
                        "Your settlement of " + s.getNetAmount().toPlainString() + " " + s.getCurrency()
                                + (provider.sandbox() ? " was recorded (sandbox - no real money)." : " was paid."), reference(s));
                return true;
            }
            if (result.status() == PayoutStatus.FAILED) {
                s.setFailureReason(result.failureReason() == null ? "Payout failed" : result.failureReason());
                s.transitionTo(SettlementStatus.FAILED);
            }
            // PENDING/PROCESSING from the provider: stays PROCESSING, re-checked by the stuck pass. Never PAID.
        } catch (RuntimeException e) {
            // Outcome unknown (timeout). Do NOT mark FAILED+resend blindly: leave PROCESSING; the stuck pass repeats the
            // idempotent call. Record only that it errored.
            s.setFailureReason("Payout call errored: " + e.getClass().getSimpleName());
        }
        settlements.save(s);
        return false;
    }

    /** Holds an unpaid settlement (dispute / fraud review). Cannot hold a settlement already being paid or paid. */
    @Transactional
    public RestaurantSettlement hold(String settlementId, String reason) {
        RestaurantSettlement s = require(settlementId);
        if (!s.getStatus().canTransitionTo(SettlementStatus.ON_HOLD)) {
            throw ApiException.conflict("A settlement that is " + s.getStatus() + " cannot be put on hold");
        }
        s.transitionTo(SettlementStatus.ON_HOLD);
        s.setHoldReason(reason == null || reason.isBlank() ? "Held by PULA" : reason.trim());
        return settlements.save(s);
    }

    /** Releases a hold: back to ELIGIBLE for an earned sale / compensation, else NOT_ELIGIBLE. */
    @Transactional
    public RestaurantSettlement release(String settlementId) {
        RestaurantSettlement s = require(settlementId);
        if (s.getStatus() != SettlementStatus.ON_HOLD) throw ApiException.conflict("This settlement is not on hold");
        boolean isEarned = s.getType() == SettlementType.MERCHANT_PROTECTION || earned.test(s);
        s.transitionTo(isEarned ? SettlementStatus.ELIGIBLE : SettlementStatus.NOT_ELIGIBLE);
        if (isEarned) s.setEligibleAt(Instant.now());
        s.setHoldReason(null);
        return settlements.save(s);
    }

    @Transactional(readOnly = true)
    public List<RestaurantSettlement> list(List<SettlementStatus> statuses, int limit) {
        return list(statuses, null, limit);
    }

    @Transactional(readOnly = true)
    public List<RestaurantSettlement> list(List<SettlementStatus> statuses, SettlementSourceType sourceType, int limit) {
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        boolean noStatus = statuses == null || statuses.isEmpty();
        if (sourceType == null) {
            return noStatus ? settlements.findAllByOrderByCreatedAtDesc(page)
                    : settlements.findByStatusInOrderByCreatedAtDesc(statuses, page);
        }
        return noStatus ? settlements.findBySourceTypeOrderByCreatedAtDesc(sourceType, page)
                : settlements.findBySourceTypeAndStatusInOrderByCreatedAtDesc(sourceType, statuses, page);
    }

    /** Notification reference: the sale id, falling back to the order id (legacy food rows) and finally the settlement id. */
    private static String reference(RestaurantSettlement s) {
        if (s.getSourceId() != null) return s.getSourceId();
        return s.getOrder() != null ? s.getOrder().getId() : s.getId();
    }

    private static String description(RestaurantSettlement s) {
        if (s.getType() == SettlementType.MERCHANT_PROTECTION) return "PULA merchant protection";
        return switch (s.getSourceType()) {
            case FOOD_ORDER -> "PULA restaurant settlement";
            case RETAIL_ORDER -> "PULA retail settlement";
            case STOCK_ORDER -> "PULA supplier settlement";
            case SERVICE_BOOKING -> "PULA service provider settlement";
        };
    }

    private RestaurantSettlement require(String id) {
        return settlements.findById(id).orElseThrow(() -> ApiException.notFound("Settlement not found"));
    }
}
