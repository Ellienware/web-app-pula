package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.Payment;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaystackGateway;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.StockOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Collects payment for B2B stock orders through the PaymentOrchestrator (MARKET_PAYMENT -> Paystack).
 *
 * Rules enforced here:
 *  - The amount charged is ALWAYS order.total computed by the backend at checkout.
 *  - An order becomes PAID only after the provider confirms success server-side
 *    (verify call) AND the confirmed amount/currency match. A webhook or client call
 *    is only a trigger to check — never proof.
 *  - One payment per order (idempotency key + unique payment_id) — no duplicate charges.
 */
@Service
public class StockPaymentService {

    private static final Logger log = LoggerFactory.getLogger(StockPaymentService.class);
    private static final String REVIEW_PREFIX = "REVIEW:";

    public record StockPaymentSession(String orderId, String reference, String checkoutUrl, String mode,
                                      boolean sandbox, BigDecimal amount, String currency,
                                      PaymentStatus paymentStatus, StockOrderStatus orderStatus) {}

    private final StockOrderRepository orderRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentOrchestrator orchestrator;
    private final BusinessService businessService;
    private final StockOrderService stockOrderService;
    private final NotificationService notificationService;
    private final String callbackUrl;
    private final long unpaidExpiryMinutes;
    private final long processingExpiryMinutes;

    public StockPaymentService(StockOrderRepository orderRepo, PaymentRepository paymentRepo,
                               PaymentOrchestrator orchestrator, BusinessService businessService,
                               StockOrderService stockOrderService, NotificationService notificationService,
                               @Value("${app.payments.paystack.callback-url:}") String callbackUrl,
                               @Value("${app.stock.unpaid-expiry-minutes:30}") long unpaidExpiryMinutes,
                               @Value("${app.stock.processing-expiry-minutes:120}") long processingExpiryMinutes) {
        this.orderRepo = orderRepo;
        this.paymentRepo = paymentRepo;
        this.orchestrator = orchestrator;
        this.businessService = businessService;
        this.stockOrderService = stockOrderService;
        this.notificationService = notificationService;
        this.callbackUrl = callbackUrl;
        this.unpaidExpiryMinutes = unpaidExpiryMinutes;
        this.processingExpiryMinutes = processingExpiryMinutes;
    }

    /** Prefix clients look for to show a confirmation prompt before retrying with acknowledged=true. */
    public static final String RISK_PREFIX = "RISK_CONFIRMATION_REQUIRED|";

    /** Phase 7 gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;

    public StockPaymentSession start(User buyer, String orderId) { return start(buyer, orderId, false); }

    /**
     * Plain-language warnings about an unusual payment (not a claim of fraud — a prompt to double-check).
     * Deterministic rules only; PULA does not claim perfect fraud detection.
     */
    public List<String> riskWarnings(StockOrder order) {
        List<String> w = new java.util.ArrayList<>();
        BigDecimal total = order.getTotal();
        if (total.compareTo(new BigDecimal("10000")) >= 0) {
            w.add("This is a large payment (" + total.toPlainString() + " " + order.getCurrency() + ").");
        }
        List<StockOrder> history = orderRepo.findTop20ByBuyerBusinessIdOrderByCreatedAtDesc(order.getBuyerBusiness().getId());
        List<StockOrder> paidBefore = history == null ? List.of() : history.stream()
                .filter(o -> !o.getId().equals(order.getId()))
                .filter(o -> o.getStatus() != StockOrderStatus.PENDING_PAYMENT
                        && o.getStatus() != StockOrderStatus.PAYMENT_PROCESSING
                        && o.getStatus() != StockOrderStatus.CANCELLED)
                .toList();
        if (paidBefore.size() >= 2) {
            BigDecimal avg = paidBefore.stream().map(StockOrder::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(paidBefore.size()), 2, java.math.RoundingMode.HALF_UP);
            if (avg.signum() > 0 && total.compareTo(avg.multiply(BigDecimal.valueOf(3))) > 0) {
                w.add("This is more than 3 times your usual stock order (about " + avg.toPlainString() + ").");
            }
        }
        boolean firstWithSupplier = paidBefore.stream().noneMatch(o ->
                o.getSupplierBusiness().getId().equals(order.getSupplierBusiness().getId()));
        if (firstWithSupplier && total.compareTo(new BigDecimal("5000")) >= 0) {
            w.add("You haven't bought from this supplier before.");
        }
        Instant created = order.getSupplierBusiness().getCreatedAt();
        if (created != null && created.isAfter(Instant.now().minus(Duration.ofDays(7)))) {
            w.add("This supplier joined PULA less than a week ago.");
        }
        return w;
    }

    /** Buyer starts (or resumes) payment for one of their own unpaid stock orders. */
    @Transactional
    public StockPaymentSession start(User buyer, String orderId, boolean riskAcknowledged) {
        Business buyerBusiness = businessService.requireOwn(buyer);
        StockOrder order = orderRepo.findByIdAndBuyerBusinessId(orderId, buyerBusiness.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));

        Payment existing = order.getPayment();
        if (existing != null) {
            if (order.getStatus() == StockOrderStatus.PAYMENT_PROCESSING && existing.getStatus() == PaymentStatus.PROCESSING) {
                return toSession(order, existing); // resume same checkout — never a second charge
            }
            throw ApiException.conflict("Payment for this order has already been started or completed");
        }
        if (order.getStatus() != StockOrderStatus.PENDING_PAYMENT) {
            throw ApiException.badRequest("This order is not awaiting payment");
        }

        List<String> warnings = riskWarnings(order);
        if (!warnings.isEmpty() && !riskAcknowledged) {
            throw ApiException.conflict(RISK_PREFIX + String.join(" ", warnings)
                    + " Only continue if you are sure this order and supplier are genuine.");
        }

        String reference = "PULA-SO-" + order.getId();
        // Phase 7 transaction-risk gate. NOTE: the client-supplied riskAcknowledged flag above is only the user's own advisory
        // acknowledgement of the supplier/order warnings. It has NO influence on the risk decision below.
        boolean simulated = "SANDBOX_MOCK".equals(orchestrator.marketMode());
        com.momocircle.risk.GateTicket riskTicket = null;
        if (riskGate != null) {
            riskTicket = riskGate.authorise(buyer, new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.STOCK_ORDER,
                    order.getId(), order.getTotal(), order.getCurrency(), order.getSupplierBusiness().getId(), buyerBusiness.getId(),
                    true, order.getSupplierBusiness().isActive(), simulated));
            riskGate.bind(buyer, riskTicket, reference, order.getTotal(), order.getCurrency(), simulated);
        }
        PaystackGateway.CheckoutSession session;
        try {
            session = orchestrator.startMarketCheckout(
                    new PaystackGateway.InitRequest(reference, order.getTotal(), order.getCurrency(),
                            buyer.getEmail(), callbackUrl));
        } catch (RuntimeException e) {
            if (riskGate != null) riskGate.recordOutcome(riskTicket, false, "provider initiation failed");
            throw e;
        }
        if (riskGate != null) riskGate.recordOutcome(riskTicket, true, "checkout started");

        Payment payment = new Payment();
        payment.setAmount(order.getTotal());
        payment.setCurrency(order.getCurrency());
        payment.setProvider(orchestrator.marketProviderLabel());
        payment.setProviderReference(reference);
        payment.setIdempotencyKey("stock-order:" + order.getId());
        payment.setPurpose(PaymentPurpose.MARKET_PAYMENT);
        payment.setCheckoutUrl(session.authorizationUrl());
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment = paymentRepo.save(payment);

        order.setPayment(payment);
        order.setStatus(StockOrderStatus.PAYMENT_PROCESSING);
        orderRepo.save(order);
        return toSession(order, payment);
    }

    /**
     * Buyer starts (or resumes) SIMULATED PULA Money wallet payment for one of their own unpaid stock orders. Same ownership,
     * status and risk rules as {@link #start}; the payment is on the wallet rail and charges NOTHING here: the debit happens in
     * StockWalletCheckoutService after an explicit confirmation of this server-computed amount.
     */
    @Transactional
    public StockPaymentSession startWallet(User buyer, String orderId, boolean riskAcknowledged) {
        Business buyerBusiness = businessService.requireOwn(buyer);
        StockOrder order = orderRepo.findByIdAndBuyerBusinessId(orderId, buyerBusiness.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));
        Payment existing = order.getPayment();
        if (existing != null) {
            if (order.getStatus() == StockOrderStatus.PAYMENT_PROCESSING && existing.getStatus() == PaymentStatus.PROCESSING
                    && com.momocircle.payment.PaymentRails.isWallet(existing)) {
                return toSession(order, existing); // resume the same wallet payment, never a second one
            }
            throw ApiException.conflict("Payment for this order has already been started or completed");
        }
        if (order.getStatus() != StockOrderStatus.PENDING_PAYMENT) {
            throw ApiException.badRequest("This order is not awaiting payment");
        }
        List<String> warnings = riskWarnings(order);
        if (!warnings.isEmpty() && !riskAcknowledged) {
            throw ApiException.conflict(RISK_PREFIX + String.join(" ", warnings)
                    + " Only continue if you are sure this order and supplier are genuine.");
        }
        String reference = "PULA-SO-" + order.getId();
        if (riskGate != null) {
            com.momocircle.risk.GateTicket ticket = riskGate.authorise(buyer, new com.momocircle.risk.GateRequest(
                    com.momocircle.risk.RiskType.STOCK_ORDER, order.getId(), order.getTotal(), order.getCurrency(),
                    order.getSupplierBusiness().getId(), buyerBusiness.getId(), true, order.getSupplierBusiness().isActive(), true));
            riskGate.bind(buyer, ticket, reference, order.getTotal(), order.getCurrency(), true);
            riskGate.recordOutcome(ticket, true, "wallet checkout started");
        }
        Payment payment = new Payment();
        payment.setAmount(order.getTotal());
        payment.setCurrency(order.getCurrency());
        payment.setProvider(com.momocircle.payment.PaymentRails.WALLET);
        payment.setProviderReference(reference);
        payment.setIdempotencyKey("stock-order:" + order.getId());
        payment.setPurpose(PaymentPurpose.MARKET_PAYMENT);
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment = paymentRepo.save(payment);
        order.setPayment(payment);
        order.setStatus(StockOrderStatus.PAYMENT_PROCESSING);
        orderRepo.save(order);
        return toSession(order, payment);
    }

    /** Buyer asks PULA to re-check their own payment (e.g. after returning from checkout). */
    @Transactional
    public StockPaymentSession refresh(User buyer, String orderId) {
        Business buyerBusiness = businessService.requireOwn(buyer);
        StockOrder order = orderRepo.findByIdAndBuyerBusinessId(orderId, buyerBusiness.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));
        if (order.getPayment() == null) throw ApiException.badRequest("Payment has not been started for this order");
        confirmByReference(order.getPayment().getProviderReference());
        StockOrder fresh = orderRepo.findById(order.getId()).orElse(order);
        return toSession(fresh, fresh.getPayment());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.OrderRepository consumerOrderRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrderService consumerOrderService;

    private enum Outcome { PAID, FAILED, PENDING, REVIEW }

    /** Optional (field-injected, like the other collaborators here) so hand-constructed unit tests are unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.ledger.LedgerTransactionRepository ledgerTxRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.ledger.LedgerPostingRepository ledgerPostingRepo;

    private Outcome verify(Payment payment, String reference) { return verify(payment, reference, null); }

    /**
     * Wallet payments (SIMULATED PULA Money) are verified from the shared ledger, never from a gateway: the linked ledger
     * transaction must be POSTED, started by the buyer, and move exactly this payment's amount. Anything else is never "paid".
     */
    private Outcome verifyWallet(Payment payment, String expectedBuyerId) {
        if (ledgerTxRepo == null || ledgerPostingRepo == null) return Outcome.PENDING;
        String txId = payment.getLedgerTransactionId();
        if (txId == null && expectedBuyerId != null) {
            // Crash window: the ledger committed but the payment row was never linked. The debit key is deterministic, so
            // adopt that transaction here rather than let the sweep expire a payment the buyer has already been debited for.
            var found = ledgerTxRepo.findByInitiatorAndKey(expectedBuyerId, com.momocircle.payment.PaymentRails.ledgerKey(payment.getId()))
                    .orElse(null);
            if (found != null) {
                payment.setLedgerTransactionId(found.getId());
                paymentRepo.save(payment);
                txId = found.getId();
            }
        }
        if (txId == null) return Outcome.PENDING;
        var tx = ledgerTxRepo.findById(txId).orElse(null);
        long expected;
        try {
            expected = com.momocircle.ledger.Money.ofDecimal(payment.getAmount(), payment.getCurrency()).minor();
        } catch (RuntimeException e) {
            expected = -1;
        }
        com.momocircle.payment.WalletPaymentVerification.Outcome r = tx == null
                ? com.momocircle.payment.WalletPaymentVerification.Outcome.REVIEW
                : com.momocircle.payment.WalletPaymentVerification.evaluate(tx.getStatus(), tx.getType(), tx.getInitiatorUserId(),
                        expectedBuyerId, tx.getCurrency(), payment.getCurrency(),
                        ledgerPostingRepo.findByTransaction(tx.getId()).stream()
                                .map(e -> new com.momocircle.ledger.Posting(e.getAccountId(), e.getDirection(), e.getAmountMinor(), e.getCurrency()))
                                .toList(),
                        expected);
        if (r == com.momocircle.payment.WalletPaymentVerification.Outcome.REVIEW) {
            log.error("Wallet payment {} does not match its ledger transaction — flagged for review", payment.getId());
            payment.setFailureReason(REVIEW_PREFIX + " wallet ledger transaction does not match the order");
            paymentRepo.save(payment);
        }
        return Outcome.valueOf(r.name());
    }

    /** Verifies with the provider (or the ledger, for wallet payments) and checks amount/currency. Never trusts a webhook or client. */
    private Outcome verify(Payment payment, String reference, String expectedBuyerId) {
        if (com.momocircle.payment.PaymentRails.isWallet(payment)) return verifyWallet(payment, expectedBuyerId);
        PaystackGateway.VerifyResult v = orchestrator.verifyMarket(reference);
        switch (v.status()) {
            case SUCCESS -> {
                boolean amountOk = v.amountMinor() == PaystackGateway.toMinor(payment.getAmount());
                boolean currencyOk = v.currency() != null && v.currency().equalsIgnoreCase(payment.getCurrency());
                if (!amountOk || !currencyOk) {
                    // Money was taken but does not match the order: never mark paid, flag for a human.
                    log.error("Payment {} confirmed by provider but amount/currency mismatch — flagged for review", payment.getId());
                    payment.setFailureReason(REVIEW_PREFIX + " amount or currency mismatch");
                    paymentRepo.save(payment);
                    return Outcome.REVIEW;
                }
                return Outcome.PAID;
            }
            case FAILED -> { return Outcome.FAILED; }
            default -> { return Outcome.PENDING; }
        }
    }

    /**
     * Idempotent, provider-verified confirmation for BOTH stock orders and consumer Market orders.
     * Safe to call from a webhook, the buyer's refresh, or the reconciliation sweep, any number of times.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyService saveToBuy;

    /** Shared settlement engine: a paid stock order opens the supplier's settlement (NOT_ELIGIBLE until delivered). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.settlement.MarketplaceSettlementService marketplaceSettlement;

    @Transactional
    public void confirmByReference(String reference) {
        if (reference == null || reference.isBlank()) return;
        Payment payment = paymentRepo.findByProviderReferenceForUpdate(reference).orElse(null);
        if (payment == null || payment.getPurpose() != PaymentPurpose.MARKET_PAYMENT) return;
        if (payment.getStatus() != PaymentStatus.PROCESSING) return; // already resolved — no-op

        StockOrder order = orderRepo.findByPaymentId(payment.getId()).orElse(null);
        if (order == null) {
            if (confirmServiceBooking(payment, reference)) return;
            confirmConsumerOrder(payment, reference);
            return;
        }

        switch (verify(payment, reference, order.getBuyerBusiness().getOwner().getId())) {
            case PAID -> {
                payment.transitionTo(PaymentStatus.SUCCESSFUL);
                paymentRepo.save(payment);
                if (order.getStatus().canTransitionTo(StockOrderStatus.PAID)) {
                    order.setStatus(StockOrderStatus.PAID);
                    orderRepo.save(order);
                    if (marketplaceSettlement != null) {
                        marketplaceSettlement.openSafely(com.momocircle.domain.enums.SettlementSourceType.STOCK_ORDER, order.getId());
                    }
                    if (saveToBuy != null && order.getSaveToBuyRequestId() != null) {
                        String sid = saveToBuy.onOrderPaid(com.momocircle.domain.enums.RelatedOrderKind.STOCK_ORDER,
                                order.getId(), order.getTotal());
                        if (sid != null) { order.setStokvelRequestId(sid); orderRepo.save(order); }
                    }
                    notificationService.notify(order.getSupplierBusiness().getOwner(), NotificationType.ORDER_RECEIVED,
                            "New paid stock order from " + order.getBuyerBusiness().getName() + ".", order.getId());
                    notificationService.notify(order.getBuyerBusiness().getOwner(), NotificationType.PAYMENT_SUCCESS,
                            "Payment received for your stock order.", order.getId());
                }
            }
            case FAILED -> {
                payment.setFailureReason("Payment was declined or failed");
                payment.transitionTo(PaymentStatus.FAILED);
                paymentRepo.save(payment);
                stockOrderService.cancelAndRelease(order);
                notificationService.notify(order.getBuyerBusiness().getOwner(), NotificationType.PAYMENT_FAILED,
                        "Payment failed for your stock order. It has been cancelled.", order.getId());
            }
            case PENDING, REVIEW -> { /* still in flight, or flagged for a human */ }
        }
    }

    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.ServiceBookingRepository serviceBookingRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ServiceBookingService serviceBookingService;

    /** Service bookings resolve through the same provider-verified path as stock and retail. */
    private boolean confirmServiceBooking(Payment payment, String reference) {
        if (serviceBookingRepo == null || serviceBookingService == null) return false;
        com.momocircle.domain.ServiceBooking b = serviceBookingRepo.findByPaymentId(payment.getId()).orElse(null);
        if (b == null) return false;
        switch (verify(payment, reference, b.getCustomer().getId())) {
            case PAID -> {
                payment.transitionTo(PaymentStatus.SUCCESSFUL);
                paymentRepo.save(payment);
                serviceBookingService.onPaymentSuccess(b);
            }
            case FAILED -> {
                payment.setFailureReason("Payment was declined or failed");
                payment.transitionTo(PaymentStatus.FAILED);
                paymentRepo.save(payment);
                serviceBookingService.onPaymentFailed(b);
            }
            case PENDING, REVIEW -> { }
        }
        return true;
    }

    private void confirmConsumerOrder(Payment payment, String reference) {
        if (consumerOrderRepo == null || consumerOrderService == null) return;
        com.momocircle.domain.Order o = consumerOrderRepo.findByPaymentId(payment.getId()).orElse(null);
        if (o == null) return;
        switch (verify(payment, reference, o.getBuyer().getId())) {
            case PAID -> {
                payment.transitionTo(PaymentStatus.SUCCESSFUL);
                paymentRepo.save(payment);
                consumerOrderService.applyResolvedSuccess(o, payment); // same side effects as a sync PAID
            }
            case FAILED -> {
                payment.setFailureReason("Payment was declined or failed");
                payment.transitionTo(PaymentStatus.FAILED);
                paymentRepo.save(payment);
                consumerOrderService.applyResolvedFailure(o); // releases reserved stock
            }
            case PENDING, REVIEW -> { }
        }
    }

    /** Sandbox only (guarded by the controller): the payment must belong to the caller's own order. */
    @Transactional(readOnly = true)
    public Payment requireOwnPaymentByReference(User buyer, String reference) {
        Payment payment = paymentRepo.findByProviderReference(reference)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        StockOrder stock = orderRepo.findByPaymentId(payment.getId()).orElse(null);
        if (stock != null) {
            Business buyerBusiness = businessService.requireOwn(buyer);
            if (!stock.getBuyerBusiness().getId().equals(buyerBusiness.getId())) throw ApiException.notFound("Payment not found");
            return payment;
        }
        if (consumerOrderRepo != null) {
            com.momocircle.domain.Order o = consumerOrderRepo.findByPaymentId(payment.getId()).orElse(null);
            if (o != null && o.getBuyer().getId().equals(buyer.getId())) return payment;
        }
        if (serviceBookingRepo != null) {
            com.momocircle.domain.ServiceBooking sb = serviceBookingRepo.findByPaymentId(payment.getId()).orElse(null);
            if (sb != null && sb.getCustomer().getId().equals(buyer.getId())) return payment;
        }
        throw ApiException.notFound("Payment not found"); // no existence leak across users
    }

    /** Cancels unpaid orders that have held reserved stock too long. */
    @Transactional
    public void expireUnpaidOrders() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(unpaidExpiryMinutes));
        List<StockOrder> stale = orderRepo.findByStatusAndCreatedAtBefore(StockOrderStatus.PENDING_PAYMENT, cutoff);
        for (StockOrder o : stale) {
            try { stockOrderService.cancelAndRelease(o); }
            catch (Exception e) { log.warn("Could not expire stock order {}: {}", o.getId(), e.getMessage()); }
        }
    }

    /** Re-verifies in-flight payments with the provider; expires ones that never resolve. */
    @Transactional
    public void reconcileProcessingOrders() {
        List<StockOrder> processing = orderRepo.findByStatusAndCreatedAtBefore(
                StockOrderStatus.PAYMENT_PROCESSING, Instant.now().plus(Duration.ofDays(1)));
        Instant expiry = Instant.now().minus(Duration.ofMinutes(processingExpiryMinutes));
        for (StockOrder o : processing) {
            try {
                Payment p = o.getPayment();
                if (p == null) continue;
                confirmByReference(p.getProviderReference());
                if (p.getStatus() == PaymentStatus.PROCESSING && o.getStatus() == StockOrderStatus.PAYMENT_PROCESSING
                        && p.getUpdatedAt().isBefore(expiry)
                        && (p.getFailureReason() == null || !p.getFailureReason().startsWith(REVIEW_PREFIX))) {
                    p.setFailureReason("Payment not completed in time");
                    p.transitionTo(PaymentStatus.FAILED);
                    paymentRepo.save(p);
                    stockOrderService.cancelAndRelease(o);
                }
            } catch (Exception e) {
                log.warn("Could not reconcile stock order {}: {}", o.getId(), e.getMessage());
            }
        }
    }

    /** Same verify-then-expire sweep for consumer Market orders paid through Paystack. */
    @Transactional
    public void reconcileConsumerPayments() {
        if (consumerOrderRepo == null || consumerOrderService == null) return;
        Instant expiry = Instant.now().minus(Duration.ofMinutes(processingExpiryMinutes));
        for (Payment p : paymentRepo.findByStatus(PaymentStatus.PROCESSING)) {
            if (p.getPurpose() != PaymentPurpose.MARKET_PAYMENT) continue;
            try {
                com.momocircle.domain.Order o = consumerOrderRepo.findByPaymentId(p.getId()).orElse(null);
                if (o == null) continue; // stock-order payments are handled in reconcileProcessingOrders
                confirmByReference(p.getProviderReference());
                if (p.getStatus() == PaymentStatus.PROCESSING && p.getUpdatedAt().isBefore(expiry)
                        && (p.getFailureReason() == null || !p.getFailureReason().startsWith(REVIEW_PREFIX))) {
                    p.setFailureReason("Payment not completed in time");
                    p.transitionTo(PaymentStatus.FAILED);
                    paymentRepo.save(p);
                    consumerOrderService.applyResolvedFailure(o);
                }
            } catch (Exception e) {
                log.warn("Could not reconcile consumer payment {}: {}", p.getId(), e.getMessage());
            }
        }
    }

    private StockPaymentSession toSession(StockOrder order, Payment p) {
        if (com.momocircle.payment.PaymentRails.isWallet(p)) {
            return new StockPaymentSession(order.getId(), p.getProviderReference(), null,
                    com.momocircle.payment.PaymentRails.WALLET_MODE, true, p.getAmount(), p.getCurrency(), p.getStatus(), order.getStatus());
        }
        String mode = orchestrator.marketMode();
        return new StockPaymentSession(order.getId(), p.getProviderReference(), p.getCheckoutUrl(), mode,
                "SANDBOX_MOCK".equals(mode), p.getAmount(), p.getCurrency(), p.getStatus(), order.getStatus());
    }
}
