package com.momocircle.service;

import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.RefundMath;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.payment.PaystackGateway.RefundRejectedException;
import com.momocircle.payment.PaystackGateway.RefundRemoteStatus;
import com.momocircle.payment.PaystackGateway.RefundRequest;
import com.momocircle.payment.PaystackGateway.RefundResult;
import com.momocircle.payment.PaystackGateway.RefundTransportException;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Refunds (PULA -> customer) for orders. One {@link PaymentRefund} row per refund operation, with a
 * deterministic unique idempotency key, so a retry, restart, duplicate webhook, admin click or a network
 * timeout after the provider accepted the request can never create a second refund.
 *
 * Paystack refunds are asynchronous: the provider accepting one (pending/processing) leaves the refund
 * PROCESSING and the order REFUND_PENDING. The order becomes REFUNDED only when the provider confirms
 * "processed" AND the cumulative refunded amount covers the payment. Legacy-provider payments (taxi-style
 * sandbox) keep their original synchronous behaviour and idempotency key ("refund_{orderId}").
 *
 * Partial refunds are supported by the model and validated (0 < amount, cumulative <= payment); only an
 * admin entry point exposes them, no customer-facing one. They are supported for hosted-checkout payments and for
 * SIMULATED wallet payments. Every refund path must hold the ORDER row lock (findByIdForUpdate) so the cumulative-total
 * check and the new refund row are serialised per order.
 */
@Service
public class OrderRefundService {

    private static final Logger log = LoggerFactory.getLogger(OrderRefundService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentProvider legacyProvider;
    private final PaymentOrchestrator orchestrator;
    private final FoodSettlementService settlements;
    private final NotificationService notificationService;
    private final int maxAttempts;

    public OrderRefundService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                              PaymentRefundRepository refundRepository, PaymentProvider legacyProvider,
                              PaymentOrchestrator orchestrator, FoodSettlementService settlements,
                              NotificationService notificationService,
                              @Value("${app.food.refund-max-attempts:10}") int maxAttempts) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.legacyProvider = legacyProvider;
        this.orchestrator = orchestrator;
        this.settlements = settlements;
        this.notificationService = notificationService;
        this.maxAttempts = maxAttempts;
    }

    public static String fullRefundKey(String orderId) { return "refund:" + orderId; }

    /** Hosted-checkout (gateway) payments. A wallet payment shares the MARKET_PAYMENT purpose but is NOT one of these. */
    static boolean isPaystack(Payment p) {
        return p.getPurpose() == PaymentPurpose.MARKET_PAYMENT && !com.momocircle.payment.PaymentRails.isWallet(p);
    }

    // ------------------------------------------------------------------ full refund (food flows + admin)

    /**
     * Drives the FULL refund of an order already in REFUND_PENDING as far as the provider allows. Safe to
     * call repeatedly. Returns true only when the order is REFUNDED, i.e. the provider has confirmed.
     */
    @Transactional
    public boolean attemptRefund(Order order) {
        if (order.getStatus() != OrderStatus.REFUND_PENDING) return false;
        Payment payment = order.getPayment();
        if (payment == null || payment.getProviderReference() == null) return false;

        if (payment.getStatus() == PaymentStatus.REFUNDED) { // crash edge: money returned, order not yet updated
            order.setStatus(OrderStatus.REFUNDED);
            orderRepository.save(order);
            notificationService.notifyOrderStatusChanged(order);
            return true;
        }
        if (payment.getStatus() != PaymentStatus.SUCCESSFUL) return false;

        CancellationCause cause = order.getCancellationCause() == null ? CancellationCause.ADMIN_CANCELLED : order.getCancellationCause();
        settleOnEnd(order, cause); // settlement cancelled or compensation created, exactly once

        List<PaymentRefund> existing = refundRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        boolean anyFailedOnly = !existing.isEmpty() && existing.stream().allMatch(r -> r.getStatus() == RefundStatus.FAILED);
        if (anyFailedOnly) return false; // the provider failed it: an admin decides (retryFailed), we never auto-resend

        PaymentRefund open = existing.stream().filter(r -> !r.getStatus().isTerminal()).findFirst().orElse(null);
        if (open == null && existing.stream().noneMatch(r -> r.getStatus() == RefundStatus.REFUNDED)) {
            BigDecimal remaining = RefundMath.remaining(payment.getAmount(), refundRepository.sumNotFailedByPayment(payment.getId()));
            if (remaining.signum() == 0) return order.getStatus() == OrderStatus.REFUNDED;
            open = openRefund(order, payment, remaining, cause, fullRefundKey(order.getId()), null, null);
        }
        if (open != null) advance(open);
        return order.getStatus() == OrderStatus.REFUNDED;
    }

    /** Same as {@link #attemptRefund(Order)} but loads the order under a row lock; for schedulers calling through the proxy. */
    @Transactional
    public boolean attemptRefundById(String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        return order != null && attemptRefund(order);
    }

    /**
     * Admin full refund of any paid order. Moves it to REFUND_PENDING, applies the settlement rule and
     * starts the refund. Returns the refund row; its status tells whether the provider has confirmed.
     */
    @Transactional
    public PaymentRefund adminFullRefund(Order order, String note) {
        Payment payment = requirePayment(order);
        if (order.getStatus() != OrderStatus.REFUND_PENDING) {
            if (!order.getStatus().canTransitionTo(OrderStatus.REFUND_PENDING)) {
                throw ApiException.badRequest("Only paid orders can be refunded");
            }
            // The settlement rule runs FIRST: it refuses (409) when the restaurant has already been paid, and that
            // must happen before any state changes rather than depend on a transaction rolling back.
            settleOnEnd(order, CancellationCause.ADMIN_CANCELLED);
            order.setCancellationCause(CancellationCause.ADMIN_CANCELLED);
            order.setStatus(OrderStatus.REFUND_PENDING);
            orderRepository.save(order);
        } else {
            settleOnEnd(order, order.getCancellationCause() == null ? CancellationCause.ADMIN_CANCELLED : order.getCancellationCause());
        }
        PaymentRefund r = refundRepository.findByIdempotencyKey(fullRefundKey(order.getId())).orElse(null);
        if (r == null) {
            BigDecimal remaining = RefundMath.remaining(payment.getAmount(), refundRepository.sumNotFailedByPayment(payment.getId()));
            if (remaining.signum() == 0) throw ApiException.badRequest("This payment has already been fully refunded");
            r = openRefund(order, payment, remaining, CancellationCause.ADMIN_CANCELLED, fullRefundKey(order.getId()), null, note);
        }
        advance(r);
        return r;
    }

    // ------------------------------------------------------------------ partial refund (admin only)

    /**
     * Refunds part of a payment. {@code operationKey} identifies the operation (the same key never makes a
     * second refund). The order's status is untouched unless the refund consumes the whole remaining
     * balance, in which case it is the full-refund path.
     */
    @Transactional
    public PaymentRefund requestPartialRefund(Order order, BigDecimal amount, CancellationCause cause,
                                              String customerNote, String merchantNote, String operationKey) {
        if (operationKey == null || operationKey.isBlank()) throw ApiException.badRequest("An operation key is required");
        Payment payment = requirePayment(order);
        if (payment.getStatus() != PaymentStatus.SUCCESSFUL) throw ApiException.badRequest("This payment is not refundable");
        String key = "refund:" + order.getId() + ":" + operationKey.trim();
        PaymentRefund existing = refundRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing; // duplicate request: same refund, never a second one
        BigDecimal remaining = RefundMath.remaining(payment.getAmount(), refundRepository.sumNotFailedByPayment(payment.getId()));
        BigDecimal valid;
        try {
            valid = RefundMath.validate(amount, payment.getAmount(), payment.getAmount().subtract(remaining));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        CancellationCause why = cause == null ? CancellationCause.ADMIN_CANCELLED : cause;
        if (valid.compareTo(remaining) == 0 && order.getStatus().canTransitionTo(OrderStatus.REFUND_PENDING)) {
            settleOnEnd(order, why); // may refuse (restaurant already paid): before any state change
            order.setCancellationCause(why);
            order.setStatus(OrderStatus.REFUND_PENDING);
            orderRepository.save(order);
        }
        PaymentRefund r = openRefund(order, payment, valid, why, key, customerNote, merchantNote);
        advance(r);
        return r;
    }

    /**
     * Admin retry of a refund the provider reported FAILED (Paystack credits the amount back to PULA, so a
     * new refund is legitimate). Creates a NEW operation; the failed row stays as history.
     */
    @Transactional
    public PaymentRefund retryFailed(String refundId) {
        PaymentRefund failed = refundRepository.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund not found"));
        if (failed.getStatus() != RefundStatus.FAILED) throw ApiException.conflict("Only a failed refund can be retried");
        Order order = failed.getOrder();
        int n = refundRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()).size();
        String key = "refund:" + order.getId() + ":retry" + n;
        PaymentRefund existing = refundRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;
        PaymentRefund next = openRefund(order, failed.getPayment(), failed.getAmount(), failed.getReason(), key,
                failed.getCustomerNote(), failed.getMerchantNote());
        advance(next);
        return next;
    }

    // ------------------------------------------------------------------ provider notifications / polling

    /**
     * A verified Paystack refund webhook. The payload is only a HINT identifying which refund to look at;
     * the state change is made from a fresh provider fetch, with amount and currency re-checked. Unknown
     * refunds are ignored (returns false); replays of a finished refund are harmless (returns true).
     */
    @Transactional
    public boolean onProviderRefundNotification(String providerRefundId, String transactionReference) {
        PaymentRefund r = null;
        if (providerRefundId != null && !providerRefundId.isBlank()) {
            r = refundRepository.findByProviderRefundReference(providerRefundId).orElse(null);
        }
        if (r == null && transactionReference != null && !transactionReference.isBlank()) {
            r = refundRepository.findOpenByOriginalReference(transactionReference).stream()
                    .filter(c -> c.getProviderRefundReference() == null).findFirst().orElse(null);
            if (r != null && providerRefundId != null && !providerRefundId.isBlank()) {
                RefundResult remote = orchestrator.fetchMarketRefund(providerRefundId);
                if (remote.status() == RefundRemoteStatus.NOT_FOUND || remote.amountMinor() != RefundMath.toMinor(r.getAmount())) {
                    return false; // not ours: amounts do not line up
                }
                r.setProviderRefundReference(providerRefundId);
            }
        }
        if (r == null) return false;
        if (transactionReference != null && !transactionReference.isBlank()
                && !transactionReference.equals(r.getOriginalReference())) {
            toNeedsAttention(r, "Webhook transaction reference does not match this refund");
            return true;
        }
        if (r.getStatus().isTerminal()) return true; // duplicate webhook
        advance(r);
        return true;
    }

    @Transactional(readOnly = true)
    public List<String> orderIdsAwaitingRefund() {
        return orderRepository.findFoodRefundsToRetry().stream().map(Order::getId).toList();
    }

    /** In-flight refunds that have not been touched for a while and whose order is not already covered by the order pass. */
    @Transactional(readOnly = true)
    public List<String> inFlightRefundIds() {
        return refundRepository.findInFlightUntouchedSince(Instant.now().minus(Duration.ofSeconds(60))).stream()
                .filter(r -> r.getOrder().getStatus() != OrderStatus.REFUND_PENDING)
                .map(PaymentRefund::getId).toList();
    }

    @Transactional
    public void advanceById(String refundId) {
        refundRepository.findById(refundId).ifPresent(this::advance);
    }

    // ------------------------------------------------------------------ internals

    private Payment requirePayment(Order order) {
        Payment p = order.getPayment();
        if (p == null) throw ApiException.badRequest("This order has no associated payment to refund");
        if (p.getProviderReference() == null) {
            throw ApiException.badRequest("This payment has no provider reference — it may not have succeeded");
        }
        return p;
    }

    private PaymentRefund openRefund(Order order, Payment payment, BigDecimal amount, CancellationCause reason,
                                     String key, String customerNote, String merchantNote) {
        PaymentRefund existing = refundRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;
        BigDecimal valid;
        try {
            valid = RefundMath.validate(amount, payment.getAmount(), refundRepository.sumNotFailedByPayment(payment.getId()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (!isPaystack(payment) && !com.momocircle.payment.PaymentRails.isWallet(payment)
                && valid.compareTo(payment.getAmount()) != 0) {
            throw ApiException.badRequest("Partial refunds are not supported for this payment method");
        }
        PaymentRefund r = new PaymentRefund();
        r.setOrder(order);
        r.setPayment(payment);
        r.setProvider(com.momocircle.payment.PaymentRails.isWallet(payment) ? com.momocircle.payment.PaymentRails.WALLET
                : isPaystack(payment) ? "paystack" : legacyProvider.name());
        r.setOriginalReference(payment.getProviderReference());
        r.setAmount(valid);
        r.setCurrency(payment.getCurrency());
        r.setReason(reason);
        r.setIdempotencyKey(key);
        r.setCustomerNote(customerNote != null ? customerNote : "Refund for order " + order.getId());
        r.setMerchantNote(merchantNote != null ? merchantNote : "PULA refund (" + reason + ") for order " + order.getId());
        try {
            return refundRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            return refundRepository.findByIdempotencyKey(key).orElseThrow(() -> e); // lost a creation race
        }
    }

    /** Moves one refund forward with whatever the provider says now. Never throws for provider trouble. */
    private void advance(PaymentRefund r) {
        if (r.getStatus().isTerminal()) return;
        if (com.momocircle.payment.PaymentRails.isWallet(r.getPayment())) advanceWallet(r);
        else if (isPaystack(r.getPayment())) advancePaystack(r);
        else advanceLegacy(r);
    }

    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.service.ledger.LedgerOperationsService ledgerOps;

    /**
     * Refunds a wallet-paid order with COMPENSATING ledger entries; posted entries are never edited. SIMULATED demo funds
     * go back to the buyer's wallet; nothing is sent to any real account and this is not a real-world refund.
     *
     * <p>Two modes, chosen by the refund's own amount: a SOLE FULL refund (amount equals the payment, which request
     * validation guarantees means no other live refund exists) reverses the original transaction exactly as before. Any
     * other refund (partial, or the remainder after earlier partials) posts a NEW transfer from the seller's wallet back
     * to the buyer under the deterministic key {@code refund:<refundId>}, so a retry replays the same ledger entry. The
     * original is then never reversed whole, which would double-refund. The cumulative cap is enforced before the refund
     * row exists (RefundMath, under the order lock); the ledger enforces the no-overdraft rule.
     *
     * <p>Runs inside the refund's own transaction: the ledger entry, the refund row and the payment/order status commit or
     * roll back together. If the seller's demo wallet no longer holds the funds the ledger refuses (it never overdraws)
     * and the refund is flagged for a person to handle instead of being reported as done. Platform fees/commission are not
     * adjusted here: the settlement engine recomputes them from the refunded total (see docs).
     */
    private void advanceWallet(PaymentRefund r) {
        Payment payment = r.getPayment();
        if (ledgerOps == null || payment.getLedgerTransactionId() == null) {
            toNeedsAttention(r, "This wallet payment has no ledger transaction to reverse");
            return;
        }
        if (r.getAttempts() >= maxAttempts) {
            toNeedsAttention(r, "Refund did not complete after " + r.getAttempts() + " attempts");
            return;
        }
        r.setAttempts(r.getAttempts() + 1);
        boolean soleFull = r.getAmount().compareTo(payment.getAmount()) == 0;
        try {
            if (soleFull) {
                ledgerOps.reverse(com.momocircle.ledger.Actor.asSystem(), payment.getLedgerTransactionId(),
                        "Order refund " + r.getOrder().getId());
                r.setProviderStatus("REVERSED");
            } else {
                var posted = ledgerOps.refundTransfer(com.momocircle.ledger.Actor.asSystem(), payment.getLedgerTransactionId(),
                        new com.momocircle.ledger.Money(RefundMath.toMinor(r.getAmount()), r.getCurrency()),
                        "refund:" + r.getId());
                r.setLedgerTransactionId(posted.tx().id());
                r.setProviderStatus("PARTIAL_REFUND_POSTED");
            }
        } catch (com.momocircle.ledger.LedgerException e) {
            switch (e.code()) {
                case INSUFFICIENT_FUNDS -> toNeedsAttention(r,
                        "The seller's demo wallet no longer holds these funds, so the refund cannot be posted automatically");
                case CONCURRENCY_FAILURE -> {
                    r.setFailureReason("The ledger was busy; this refund will be retried");
                    refundRepository.save(r);
                }
                default -> toNeedsAttention(r, "The ledger refused the refund (" + e.code() + ")");
            }
            return;
        }
        complete(r);
    }

    private void advanceLegacy(PaymentRefund r) {
        if (r.getAttempts() >= maxAttempts) { toNeedsAttention(r, "Refund did not complete after " + r.getAttempts() + " attempts"); return; }
        r.setAttempts(r.getAttempts() + 1);
        Payment payment = r.getPayment();
        ChargeResult result;
        try {
            result = legacyProvider.refund(payment.getProviderReference(), "refund_" + r.getOrder().getId());
        } catch (RuntimeException e) {
            log.warn("Refund attempt for order {} failed, will retry: {}", r.getOrder().getId(), e.getMessage());
            r.setFailureReason("Provider error: " + e.getClass().getSimpleName());
            refundRepository.save(r);
            return;
        }
        r.setProviderStatus(String.valueOf(result.status()));
        if (result.status() == PaymentStatus.REFUNDED) {
            if (result.providerReference() != null) payment.setProviderReference(result.providerReference());
            complete(r);
        } else {
            refundRepository.save(r);
        }
    }

    private void advancePaystack(PaymentRefund r) {
        if (r.getProviderRefundReference() == null) {
            // A previous create call may have been accepted without us hearing back. Look before creating again.
            if (r.getAttempts() > 0) {
                List<RefundResult> remote;
                try {
                    remote = orchestrator.listMarketRefunds(r.getOriginalReference());
                } catch (RefundTransportException e) {
                    r.setFailureReason(e.getMessage());
                    refundRepository.save(r);
                    return; // cannot tell whether one exists: do NOT create another
                }
                long minor = RefundMath.toMinor(r.getAmount());
                RefundResult adopt = remote.stream()
                        .filter(x -> x.providerRefundId() != null && x.amountMinor() == minor
                                && x.status() != RefundRemoteStatus.FAILED
                                && refundRepository.findByProviderRefundReference(x.providerRefundId()).isEmpty())
                        .findFirst().orElse(null);
                if (adopt != null) {
                    r.setProviderRefundReference(adopt.providerRefundId());
                    applyProviderResult(r, adopt);
                    return;
                }
            }
            if (r.getAttempts() >= maxAttempts) { toNeedsAttention(r, "Provider unreachable after " + r.getAttempts() + " attempts"); return; }
            r.setAttempts(r.getAttempts() + 1);
            // Claim this attempt BEFORE calling out: a concurrent caller's flush then fails on the version
            // check, so only one of them ever reaches the provider.
            refundRepository.saveAndFlush(r);
            RefundResult res;
            try {
                res = orchestrator.refundMarket(new RefundRequest(r.getOriginalReference(), RefundMath.toMinor(r.getAmount()),
                        r.getCurrency(), r.getCustomerNote(), r.getMerchantNote()));
            } catch (RefundRejectedException e) {
                toFailed(r, e.getMessage()); // a definite "no" from Paystack
                return;
            } catch (RefundTransportException e) {
                r.setFailureReason(e.getMessage()); // outcome unknown: stays PENDING, next pass looks before retrying
                refundRepository.save(r);
                return;
            }
            applyProviderResult(r, res);
            return;
        }
        RefundResult res;
        try {
            res = orchestrator.fetchMarketRefund(r.getProviderRefundReference());
        } catch (RefundTransportException e) {
            r.setFailureReason(e.getMessage());
            refundRepository.save(r);
            return;
        }
        applyProviderResult(r, res);
    }

    /** Turns what the provider reported into our state, re-checking amount and currency first. */
    private void applyProviderResult(PaymentRefund r, RefundResult res) {
        r.setProviderStatus(res.rawStatus());
        if (res.providerRefundId() != null && r.getProviderRefundReference() == null) {
            r.setProviderRefundReference(res.providerRefundId());
        }
        if (res.status() != RefundRemoteStatus.NOT_FOUND) {
            boolean amountBad = res.amountMinor() > 0 && res.amountMinor() != RefundMath.toMinor(r.getAmount());
            boolean currencyBad = res.currency() != null && !res.currency().equalsIgnoreCase(r.getCurrency());
            if (amountBad || currencyBad) {
                toNeedsAttention(r, "Provider refund amount or currency does not match what PULA requested");
                return;
            }
        }
        switch (res.status()) {
            case PENDING, PROCESSING -> {
                if (r.getStatus() == RefundStatus.PENDING || r.getStatus() == RefundStatus.NEEDS_ATTENTION) {
                    r.transitionTo(RefundStatus.PROCESSING);
                }
                r.setFailureReason(null);
                refundRepository.save(r);
            }
            case PROCESSED -> complete(r);
            case FAILED -> toFailed(r, "The provider could not process this refund");
            case NEEDS_ATTENTION -> toNeedsAttention(r, "The provider needs manual action on this refund");
            case NOT_FOUND -> toNeedsAttention(r, "The provider has no record of this refund");
        }
    }

    private void complete(PaymentRefund r) {
        r.transitionTo(RefundStatus.REFUNDED);
        r.setProcessedAt(Instant.now());
        r.setFailureReason(null);
        refundRepository.save(r);
        Order order = r.getOrder();
        Payment payment = r.getPayment();
        settleOnRefund(order);
        BigDecimal refunded = refundRepository.sumRefundedByOrder(order.getId());
        if (refunded.compareTo(payment.getAmount()) >= 0) { // only a fully-covered payment is REFUNDED
            if (payment.getStatus() == PaymentStatus.SUCCESSFUL) payment.transitionTo(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);
            if (order.getStatus() == OrderStatus.REFUND_PENDING) {
                order.setStatus(OrderStatus.REFUNDED);
                orderRepository.save(order);
                notificationService.notifyOrderStatusChanged(order);
            }
        }
    }

    private void toFailed(PaymentRefund r, String why) {
        r.setFailureReason(why);
        if (r.getStatus().canTransitionTo(RefundStatus.FAILED)) r.transitionTo(RefundStatus.FAILED);
        r.setProcessedAt(Instant.now());
        refundRepository.save(r);
        log.warn("Refund {} for order {} FAILED: {}", r.getId(), r.getOrder().getId(), why);
    }

    private void toNeedsAttention(PaymentRefund r, String why) {
        r.setFailureReason(why);
        if (r.getStatus().canTransitionTo(RefundStatus.NEEDS_ATTENTION)) r.transitionTo(RefundStatus.NEEDS_ATTENTION);
        refundRepository.save(r);
        log.warn("Refund {} for order {} needs attention: {}", r.getId(), r.getOrder().getId(), why);
    }

    /** Shared marketplace settlement engine (retail orders). Optional so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.settlement.MarketplaceSettlementService marketplace;

    /** Food keeps its cause-based rule; every other order ends its settlement through the shared engine. */
    private void settleOnEnd(Order order, CancellationCause cause) {
        if (order.isFood()) {
            settlements.onOrderEndedWithRefund(order, cause);
            return;
        }
        if (marketplace != null && !marketplace.onCancelled(com.momocircle.domain.enums.SettlementSourceType.RETAIL_ORDER, order.getId())) {
            throw ApiException.conflict("The seller has already been paid for this order. Handle it as a dispute instead of an automatic refund.");
        }
    }

    private void settleOnRefund(Order order) {
        if (order.isFood()) {
            settlements.onRefundProcessed(order);
        } else if (marketplace != null) {
            marketplace.onRefundChanged(com.momocircle.domain.enums.SettlementSourceType.RETAIL_ORDER, order.getId());
        }
    }
}
