package com.momocircle.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.momocircle.config.SettlementProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.payment.MockPaystackGateway;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.payment.PaystackGateway;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-ins for the refund / settlement / order repositories, with the SAME semantics as the
 * real SQL (unique keys, atomic claims), wired to the REAL OrderRefundService and FoodSettlementService.
 * Only the outside world is faked: Paystack (MockPaystackGateway or a Mockito gateway), the payout
 * provider and notifications.
 */
final class FoodFinanceFixture {

    final Map<String, PaymentRefund> refunds = new LinkedHashMap<>();
    final Map<String, RestaurantSettlement> settlementsByOrder = new LinkedHashMap<>();
    final Map<String, Order> orders = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);

    final PaymentRefundRepository refundRepo = mock(PaymentRefundRepository.class);
    final RestaurantSettlementRepository settlementRepo = mock(RestaurantSettlementRepository.class);
    final OrderRepository orderRepo = mock(OrderRepository.class);
    final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    final NotificationService notifications = mock(NotificationService.class);
    final PaymentProvider legacy = mock(PaymentProvider.class);
    final PayoutProvider payoutProvider = mock(PayoutProvider.class);
    final SettlementProperties props = new SettlementProperties();
    final MockPaystackGateway mockGateway = new MockPaystackGateway();
    final List<PayoutProvider.PayoutRequest> payoutRequests = new ArrayList<>();

    PaystackGateway gateway = mockGateway;
    FoodSettlementService settlements;
    OrderRefundService refundService;
    int maxRefundAttempts = 10;

    User owner = new User();
    User buyer = new User();
    Business business = new Business();

    FoodFinanceFixture() {
        owner.setId("owner-1");
        owner.setPhoneNumber("0821234567");
        buyer.setId("buyer-1");
        buyer.setFullName("Thandi");
        business.setId("biz-1");
        business.setName("Gogo's Kitchen");
        business.setOwner(owner);
        business.setBusinessType(BusinessType.HOME_KITCHEN);
        wireRepos();
        when(legacy.name()).thenReturn("sandbox");
        when(payoutProvider.name()).thenReturn("sandbox");
        when(payoutProvider.sandbox()).thenReturn(true);
        when(payoutProvider.pay(any())).thenAnswer(i -> {
            PayoutProvider.PayoutRequest r = i.getArgument(0);
            if (r == null) return null; // only seen while a test re-stubs pay(any())
            payoutRequests.add(r);
            return new PayoutProvider.PayoutResult(PayoutStatus.SUCCESSFUL, "SANDBOX-" + r.idempotencyKey().hashCode(), null);
        });
        build();
    }

    /** (Re)creates the real services around the current gateway/props. Call after swapping {@link #gateway}. */
    void build() {
        settlements = new FoodSettlementService(settlementRepo, refundRepo, new PayoutOrchestrator(payoutProvider), props, notifications);
        refundService = new OrderRefundService(orderRepo, paymentRepo, refundRepo, legacy,
                new PaymentOrchestrator(gateway, legacy), settlements, notifications, maxRefundAttempts);
    }

    private void wireRepos() {
        when(orderRepo.save(any(Order.class))).thenAnswer(i -> { Order o = i.getArgument(0); orders.put(o.getId(), o); return o; });
        when(orderRepo.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(orders.get(i.<String>getArgument(0))));
        when(orderRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(orders.get(i.<String>getArgument(0))));
        when(orderRepo.findFoodRefundsToRetry()).thenAnswer(i -> orders.values().stream()
                .filter(o -> o.getStatus() == OrderStatus.REFUND_PENDING && o.isFood()).toList());
        when(paymentRepo.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        when(refundRepo.save(any(PaymentRefund.class))).thenAnswer(i -> storeRefund(i.getArgument(0)));
        when(refundRepo.saveAndFlush(any(PaymentRefund.class))).thenAnswer(i -> storeRefund(i.getArgument(0)));
        when(refundRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(refunds.get(i.<String>getArgument(0))));
        when(refundRepo.findByIdempotencyKey(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        when(refundRepo.findByProviderRefundReference(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> i.getArgument(0).equals(r.getProviderRefundReference())).findFirst());
        when(refundRepo.findByOrderIdOrderByCreatedAtAsc(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getOrder().getId().equals(i.getArgument(0))).toList());
        when(refundRepo.sumNotFailedByPayment(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getPayment().getId().equals(i.getArgument(0)) && r.getStatus() != RefundStatus.FAILED)
                .map(PaymentRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(refundRepo.sumRefundedByOrder(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getOrder().getId().equals(i.getArgument(0)) && r.getStatus() == RefundStatus.REFUNDED)
                .map(PaymentRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(refundRepo.findOpenByOriginalReference(anyString())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getOriginalReference().equals(i.getArgument(0)) && !r.getStatus().isTerminal()).toList());
        when(refundRepo.findInFlightUntouchedSince(any())).thenAnswer(i -> refunds.values().stream()
                .filter(r -> r.getStatus() == RefundStatus.PENDING || r.getStatus() == RefundStatus.PROCESSING).toList());

        when(settlementRepo.save(any(RestaurantSettlement.class))).thenAnswer(i -> storeSettlement(i.getArgument(0)));
        when(settlementRepo.saveAndFlush(any(RestaurantSettlement.class))).thenAnswer(i -> storeSettlement(i.getArgument(0)));
        when(settlementRepo.findByOrderId(anyString())).thenAnswer(i -> Optional.ofNullable(settlementsByOrder.get(i.<String>getArgument(0))));
        when(settlementRepo.findById(anyString())).thenAnswer(i -> settlementsByOrder.values().stream()
                .filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
        // The real claims are single UPDATE statements: exactly one caller can win. Same here (synchronized).
        when(settlementRepo.claimForPayout(anyString(), any())).thenAnswer(i -> {
            synchronized (settlementsByOrder) {
                RestaurantSettlement s = settlementsByOrder.values().stream().filter(x -> x.getId().equals(i.getArgument(0))).findFirst().orElse(null);
                if (s == null || (s.getStatus() != SettlementStatus.ELIGIBLE && s.getStatus() != SettlementStatus.FAILED)) return 0;
                s.transitionTo(SettlementStatus.PROCESSING);
                s.setPayoutAttempts(s.getPayoutAttempts() + 1);
                return 1;
            }
        });
        when(settlementRepo.claimStuck(anyString(), any(), any())).thenAnswer(i -> {
            synchronized (settlementsByOrder) {
                RestaurantSettlement s = settlementsByOrder.values().stream().filter(x -> x.getId().equals(i.getArgument(0))).findFirst().orElse(null);
                if (s == null || s.getStatus() != SettlementStatus.PROCESSING) return 0;
                s.setPayoutAttempts(s.getPayoutAttempts() + 1);
                return 1;
            }
        });
        when(settlementRepo.findPayable(any())).thenAnswer(i -> settlementsByOrder.values().stream()
                .filter(s -> s.getStatus() == SettlementStatus.ELIGIBLE && !s.getEligibleAt().isAfter(i.getArgument(0))).toList());
        when(settlementRepo.findRetryable(anyInt())).thenAnswer(i -> settlementsByOrder.values().stream()
                .filter(s -> s.getStatus() == SettlementStatus.FAILED && s.getPayoutAttempts() < (int) i.getArgument(0)).toList());
        when(settlementRepo.findStuckProcessing(any())).thenAnswer(i -> settlementsByOrder.values().stream()
                .filter(s -> s.getStatus() == SettlementStatus.PROCESSING).toList());
    }

    private PaymentRefund storeRefund(PaymentRefund r) {
        if (r.getId() == null) r.setId("rf-" + ids.getAndIncrement());
        // The real table has a UNIQUE(idempotency_key): a second row with the same key is rejected.
        boolean dup = refunds.values().stream().anyMatch(x -> x != r && x.getIdempotencyKey().equals(r.getIdempotencyKey()));
        if (dup) throw new org.springframework.dao.DataIntegrityViolationException("uq_payment_refunds_idem");
        refunds.put(r.getId(), r);
        return r;
    }

    private RestaurantSettlement storeSettlement(RestaurantSettlement s) {
        if (s.getId() == null) s.setId("st-" + ids.getAndIncrement());
        RestaurantSettlement existing = settlementsByOrder.get(s.getOrder().getId());
        if (existing != null && existing != s) throw new org.springframework.dao.DataIntegrityViolationException("uq_restaurant_settlements_order");
        settlementsByOrder.put(s.getOrder().getId(), s);
        return s;
    }

    // ------------------------------------------------------------------ order builders

    /** A food order whose R120 payment (R100 food + R20 delivery) has SUCCEEDED, with Paystack really holding the money. */
    Order paidFoodOrder(String id, boolean paystack) {
        Payment p = new Payment();
        p.setId("pay-" + id);
        p.setAmount(new BigDecimal("120.00"));
        p.setCurrency("ZAR");
        p.transitionTo(PaymentStatus.PROCESSING);
        p.transitionTo(PaymentStatus.SUCCESSFUL);
        p.setProviderReference("TX-" + id);
        if (paystack) {
            p.setPurpose(PaymentPurpose.MARKET_PAYMENT);
            p.setProvider("paystack-mock");
            mockGateway.initialize(new PaystackGateway.InitRequest("TX-" + id, p.getAmount(), "ZAR", "a@b.c", "cb"));
            mockGateway.simulateCustomerPaid("TX-" + id);
        }
        Order o = new Order();
        o.setId(id);
        o.setBuyer(buyer);
        o.setBusiness(business);
        o.setKind(OrderKind.FOOD);
        o.setStatus(OrderStatus.PAID);
        o.setRestaurantDecision(RestaurantDecision.PENDING);
        o.setSubtotal(new BigDecimal("100.00"));
        o.setDeliveryFeeSnapshot(new BigDecimal("20.00"));
        o.setTotal(new BigDecimal("120.00"));
        o.setCurrency("ZAR");
        o.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        o.setPayment(p);
        orders.put(id, o);
        return o;
    }

    /** Moves an order the way the real flow does. */
    Order accepted(Order o) { o.setRestaurantDecision(RestaurantDecision.ACCEPTED); o.setStatus(OrderStatus.PROCESSING); return o; }
    Order ready(Order o) { o.setStatus(OrderStatus.READY); o.setReadyAt(Instant.now()); return o; }

    RestaurantSettlement settlementOf(String orderId) { return settlementsByOrder.get(orderId); }
}
