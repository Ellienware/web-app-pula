package com.momocircle.service;

import com.momocircle.finance.MarketplaceSettlementCalculator;
import com.momocircle.settlement.SettlementPayoutService;
import com.momocircle.config.SettlementProperties;
import com.momocircle.domain.Order;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.domain.enums.SettlementType;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.SettlementCalculator;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PULA Food restaurant settlement lifecycle (PULA -> restaurant), kept apart from driver earnings.
 *
 *   payment succeeds      -> NOT_ELIGIBLE row (nothing is payable yet)
 *   order COMPLETED       -> ELIGIBLE after the configured dispute window
 *   payout confirmed      -> PAID  (never merely because a payout was requested)
 *   rejected / expired / cancelled before prep / payment failed -> CANCELLED
 *   PULA delivery failure after prep -> MERCHANT_PROTECTION compensation, ELIGIBLE (policy permitting)
 *
 * One row per order (unique) and a deterministic idempotency key, so an order can never be settled
 * twice. Every amount is computed here from database state; no client supplies any figure.
 */
@Service
public class FoodSettlementService {

    private static final Logger log = LoggerFactory.getLogger(FoodSettlementService.class);

    private final RestaurantSettlementRepository settlements;
    private final PaymentRefundRepository refunds;
    private final PayoutOrchestrator payoutOrchestrator;
    private final SettlementProperties props;
    private final NotificationService notificationService;

    public FoodSettlementService(RestaurantSettlementRepository settlements, PaymentRefundRepository refunds,
                                 PayoutOrchestrator payoutOrchestrator, SettlementProperties props,
                                 NotificationService notificationService) {
        this.settlements = settlements;
        this.refunds = refunds;
        this.payoutOrchestrator = payoutOrchestrator;
        this.props = props;
        this.notificationService = notificationService;
    }

    public static String settlementKey(String orderId) { return "settlement:" + orderId; }

    // ------------------------------------------------------------------ lifecycle hooks

    /** Called once the customer's payment for a food order has succeeded. Idempotent. */
    @Transactional
    public RestaurantSettlement openForPaidOrder(Order order) {
        if (!order.isFood()) return null;
        return settlements.findByOrderId(order.getId()).orElseGet(() -> create(order));
    }

    /** The order reached COMPLETED (delivered or collected): the restaurant has earned its settlement. Idempotent. */
    @Transactional
    public void onOrderCompleted(Order order) {
        if (!order.isFood()) return;
        RestaurantSettlement s = settlements.findByOrderId(order.getId()).orElseGet(() -> create(order));
        if (s.getType() != SettlementType.SALE || s.getStatus() != SettlementStatus.NOT_ELIGIBLE) {
            return; // already eligible/paid/held/cancelled: completing again changes nothing
        }
        recalculate(s, order, SettlementType.SALE);
        s.transitionTo(SettlementStatus.ELIGIBLE);
        s.setEligibleAt(Instant.now().plus(Duration.ofMinutes(Math.max(0, props.getDelayMinutes()))));
        settlements.save(s);
    }

    /**
     * A food order ended with a refund to the customer. Applies the cause-based rule exactly once:
     * restaurant fault, payment failure, or an unprepared order -> the settlement is CANCELLED; PULA
     * could not deliver prepared food (or policy says so for other causes) -> merchant-protection
     * compensation. Idempotent: a second call finds the settlement already decided and does nothing.
     *
     * Refuses when the restaurant has already been paid (or is being paid) for a completed sale: that is
     * a clawback/dispute that needs a human, not an automatic refund.
     */
    @Transactional
    public void onOrderEndedWithRefund(Order order, CancellationCause cause) {
        if (!order.isFood()) return;
        CancellationCause effective = cause == null ? CancellationCause.ADMIN_CANCELLED : cause;
        RestaurantSettlement s = settlements.findByOrderId(order.getId()).orElse(null);

        if (s != null && s.getType() == SettlementType.MERCHANT_PROTECTION) return; // already compensated: idempotent
        if (s != null && s.getType() == SettlementType.SALE
                && (s.getStatus() == SettlementStatus.PAID || s.getStatus() == SettlementStatus.PROCESSING)) {
            throw ApiException.conflict("The restaurant has already been paid for this order. "
                    + "Handle it as a dispute instead of an automatic refund.");
        }
        if (s != null && s.getStatus() == SettlementStatus.CANCELLED) return; // already decided

        if (owesCompensation(order, effective)) {
            if (s == null) s = create(order);
            convertToCompensation(s, order, effective);
            settlements.save(s);
            notificationService.notify(order.getBusiness().getOwner(), NotificationType.SYSTEM,
                    "PULA could not deliver an order you prepared. You will be compensated "
                            + s.getNetAmount().toPlainString() + " " + s.getCurrency() + ".", order.getId());
            log.info("Merchant-protection compensation {} created for order {} (cause {})", s.getId(), order.getId(), effective);
            return;
        }
        if (s != null) {
            s.transitionTo(SettlementStatus.CANCELLED);
            s.setCause(effective);
            s.setNetAmount(BigDecimal.ZERO);
            settlements.save(s);
        }
    }

    /** A refund (possibly partial) completed: re-price a settlement that has not been paid out yet. */
    @Transactional
    public void onRefundProcessed(Order order) {
        if (!order.isFood()) return;
        RestaurantSettlement s = settlements.findByOrderId(order.getId()).orElse(null);
        if (s == null || s.getType() != SettlementType.SALE) return;
        switch (s.getStatus()) {
            case NOT_ELIGIBLE, ELIGIBLE, ON_HOLD, FAILED -> {
                recalculate(s, order, SettlementType.SALE);
                settlements.save(s);
            }
            default -> { } // PROCESSING / PAID / CANCELLED: amounts are frozen
        }
    }

    // ------------------------------------------------------------------ policy

    /** Prepared food, a cause that may owe compensation, and the configured policy says yes. Never for restaurant fault. */
    boolean owesCompensation(Order order, CancellationCause cause) {
        if (cause.isRestaurantFault() || !cause.mayOweCompensation()) return false;
        if (order.getReadyAt() == null) return false; // food was never prepared: nothing to compensate
        return switch (cause) {
            case PULA_DELIVERY_FAILURE -> props.isCompensatePulaDeliveryFailure();
            case CUSTOMER_CANCELLED -> props.isCompensateCustomerCancelledAfterPrep();
            case ADMIN_CANCELLED -> props.isCompensateAdminCancelledAfterPrep();
            default -> false;
        };
    }

    // ------------------------------------------------------------------ calculation

    private RestaurantSettlement create(Order order) {
        RestaurantSettlement s = new RestaurantSettlement();
        s.setOrder(order);
        s.setSourceType(com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER);
        s.setSourceId(order.getId());
        s.setSellerKind(com.momocircle.domain.enums.SellerKind.RESTAURANT);
        s.setBusiness(order.getBusiness());
        s.setPayment(order.getPayment());
        s.setCurrency(order.getCurrency());
        s.setIdempotencyKey(settlementKey(order.getId()));
        s.setFoodSubtotal(nz(order.getSubtotal()));
        s.setDeliveryAllocation(nz(order.getDeliveryFeeSnapshot()));
        recalculate(s, order, SettlementType.SALE);
        try {
            return settlements.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            // Lost a creation race: the unique order_id / key means someone else just made it. Use theirs.
            return settlements.findByOrderId(order.getId()).orElseThrow(() -> e);
        }
    }

    private void convertToCompensation(RestaurantSettlement s, Order order, CancellationCause cause) {
        s.setType(SettlementType.MERCHANT_PROTECTION);
        s.setCause(cause);
        recalculate(s, order, SettlementType.MERCHANT_PROTECTION);
        s.setPulaFulfilmentLoss(s.getNetAmount());
        if (s.getStatus() != SettlementStatus.ELIGIBLE) {
            if (s.getStatus() == SettlementStatus.FAILED || s.getStatus() == SettlementStatus.ON_HOLD) {
                // keep a deliberate hold/failed state for an admin to resolve; do not auto-release
                return;
            }
            s.transitionTo(SettlementStatus.ELIGIBLE);
        }
        s.setEligibleAt(Instant.now().plus(Duration.ofMinutes(Math.max(0, props.getDelayMinutes()))));
    }

    /**
     * Recomputes the money fields from the order and current refunds. Delivery fees are never part of it.
     * For compensation, the customer's refund is irrelevant: the restaurant is paid for the food it
     * prepared, so refunds are not deducted (the customer refund is PULA's cost, not the restaurant's).
     */
    private void recalculate(RestaurantSettlement s, Order order, SettlementType type) {
        BigDecimal refunded = type == SettlementType.MERCHANT_PROTECTION ? BigDecimal.ZERO
                : nz(refunds.sumRefundedByOrder(order.getId()));
        BigDecimal rate = type == SettlementType.MERCHANT_PROTECTION ? props.getCompensationCommissionRate() : props.getCommissionRate();
        BigDecimal fee = type == SettlementType.MERCHANT_PROTECTION ? BigDecimal.ZERO : props.getFixedFee();
        // The shared formula (identical to the old food calculator while payment fee and adjustments are zero).
        MarketplaceSettlementCalculator.Result r = MarketplaceSettlementCalculator.calculate(nz(order.getSubtotal()), refunded,
                s.getAdjustmentAmount(), MarketplaceSettlementCalculator.Rates.of(rate, fee));
        s.setGrossAmount(r.gross());
        s.setPaymentFeeAmount(r.paymentFee());
        s.setFoodSubtotal(r.gross());
        s.setDeliveryAllocation(nz(order.getDeliveryFeeSnapshot()));
        s.setRefundAmount(r.refunded());
        s.setEligibleRevenue(r.eligible());
        s.setCommissionRate(r.commissionRate());
        s.setCommissionAmount(r.commission());
        s.setOtherFees(r.fixedFee());
        s.setNetAmount(r.net());
    }

    // ------------------------------------------------------------------ payout

    /** One settlement the scheduler should drive; {@code stuck} = already PROCESSING, so the idempotent call is repeated. */
    public record DuePayout(String settlementId, boolean stuck) {}

    // ------------------------------------------------------------------ payout machinery (shared with every source)
    // Delegated to the single SettlementPayoutService; behaviour is unchanged. Food keeps its own lifecycle above.

    /** Shared payout engine, built from this service's own dependencies so the constructor stays as it was. */
    private SettlementPayoutService payoutService() {
        if (payoutService == null) {
            payoutService = new SettlementPayoutService(settlements, payoutOrchestrator, notificationService,
                    props.getMaxPayoutAttempts(), props.getStuckProcessingMinutes(),
                    s -> s.getOrder() != null && s.getOrder().getStatus() == com.momocircle.domain.enums.OrderStatus.COMPLETED);
        }
        return payoutService;
    }

    private SettlementPayoutService payoutService;

    @Transactional(readOnly = true)
    public List<DuePayout> dueForPayout() {
        return payoutService().dueForPayout().stream().map(d -> new DuePayout(d.settlementId(), d.stuck())).toList();
    }

    @Transactional
    public boolean pay(String settlementId, boolean stuck) {
        return payoutService().pay(settlementId, stuck);
    }

    @Transactional
    public RestaurantSettlement hold(String settlementId, String reason) {
        return payoutService().hold(settlementId, reason);
    }

    @Transactional
    public RestaurantSettlement release(String settlementId) {
        return payoutService().release(settlementId);
    }

    /** Food settlements only: the food admin screens never show retail, stock or service rows. */
    @Transactional(readOnly = true)
    public List<RestaurantSettlement> list(List<SettlementStatus> statuses, int limit) {
        return payoutService().list(statuses, com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER, limit);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
