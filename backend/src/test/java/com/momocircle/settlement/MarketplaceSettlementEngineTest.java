package com.momocircle.settlement;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.exception.ApiException;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.repository.*;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.NotificationService;
import com.momocircle.settlement.SettlementDtos.*;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL settlement engine, policies, payout machinery, reconciliation and insights against in-memory fakes
 * (Proxy repositories, a fake PayoutProvider, subclassed collaborators): no Spring, no Mockito, no database. The
 * Food-specific lifecycle in FoodSettlementService is not executed here (it needs its own Mockito suite to run).
 */
class MarketplaceSettlementEngineTest {

    interface Custom { Object handle(String name, Object[] a, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save", "saveAndFlush" -> { BaseEntity e = (BaseEntity) args[0]; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); return e; }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "findAll" -> { return new ArrayList<>(store.values()); }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                default -> { return custom.handle(m.getName(), args, store); }
            }
        });
    }

    static void set(Object target, Class<?> declaring, String field, Object value) {
        try { Field f = declaring.getDeclaredField(field); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    static BigDecimal d(String v) { return new BigDecimal(v); }

    static void expect(String status, Runnable r) {
        assertEquals(status, assertThrows(ApiException.class, r::run).getStatus().name());
    }

    // ------------------------------------------------------------------ world
    static final class World {
        enum PayoutMode { SUCCESS, FAILED, THROW, PENDING }

        final Map<String, Object> settlementStore = new LinkedHashMap<>(), adjustmentStore = new LinkedHashMap<>(), orderStore = new LinkedHashMap<>(),
                stockStore = new LinkedHashMap<>(), bookingStore = new LinkedHashMap<>(), businessStore = new LinkedHashMap<>();
        final Map<String, BigDecimal> refundedByOrder = new HashMap<>();
        final Map<String, Business> businessByOwner = new HashMap<>();
        final List<String> audits = new ArrayList<>(), notes = new ArrayList<>();
        final List<PayoutProvider.PayoutRequest> payoutCalls = new ArrayList<>();
        PayoutMode mode = PayoutMode.SUCCESS;

        final MarketplaceSettlementProperties props = new MarketplaceSettlementProperties();
        final com.momocircle.config.SettlementProperties foodProps = new com.momocircle.config.SettlementProperties();
        MarketplaceSettlementService engine;
        MarketplaceReconciliationService reconciliation;
        SettlementInsightService insights;
        SettlementPayoutService payoutService;
        RestaurantSettlementRepository settlementRepo;

        @SuppressWarnings("unchecked")
        World() {
            props.getRetail().setCommissionRate(d("0.10"));
            props.getRetail().setFixedFee(d("2.00"));
            props.getRetail().setPaymentFeeRate(d("0.015"));
            props.getRetail().setPaymentFeeFixed(d("0.50"));
            props.getRetail().setDisputeWindowMinutes(60);
            props.getStock().setCommissionRate(d("0.05"));
            props.getService().setCommissionRate(d("0.12"));
            foodProps.setCommissionRate(d("0.15"));

            settlementRepo = repo(RestaurantSettlementRepository.class, settlementStore, (n, a, s) -> {
                List<RestaurantSettlement> l = all(s, RestaurantSettlement.class);
                switch (n) {
                    case "findBySourceTypeAndSourceId": return l.stream().filter(x -> x.getSourceType() == a[0] && Objects.equals(x.getSourceId(), a[1])).findFirst();
                    case "findByIdForUpdate": return Optional.ofNullable(s.get((String) a[0]));
                    case "claimForPayout": {
                        RestaurantSettlement x = (RestaurantSettlement) s.get((String) a[0]);
                        if (x == null || (x.getStatus() != SettlementStatus.ELIGIBLE && x.getStatus() != SettlementStatus.FAILED)) return 0;
                        x.transitionTo(SettlementStatus.PROCESSING);
                        set(x, RestaurantSettlement.class, "payoutAttempts", x.getPayoutAttempts() + 1);
                        set(x, BaseEntity.class, "updatedAt", (Instant) a[1]);
                        return 1;
                    }
                    case "claimStuck": {
                        RestaurantSettlement x = (RestaurantSettlement) s.get((String) a[0]);
                        if (x == null || x.getStatus() != SettlementStatus.PROCESSING || !x.getUpdatedAt().isBefore((Instant) a[2])) return 0;
                        set(x, RestaurantSettlement.class, "payoutAttempts", x.getPayoutAttempts() + 1);
                        set(x, BaseEntity.class, "updatedAt", (Instant) a[1]);
                        return 1;
                    }
                    case "findPayable": return l.stream().filter(x -> x.getStatus() == SettlementStatus.ELIGIBLE && !x.getEligibleAt().isAfter((Instant) a[0])).toList();
                    case "findRetryable": return l.stream().filter(x -> x.getStatus() == SettlementStatus.FAILED && x.getPayoutAttempts() < (int) a[0]).toList();
                    case "findStuckProcessing": return l.stream().filter(x -> x.getStatus() == SettlementStatus.PROCESSING && x.getUpdatedAt().isBefore((Instant) a[0])).toList();
                    case "findAllByOrderByCreatedAtDesc": return l;
                    case "findByStatusInOrderByCreatedAtDesc": return l.stream().filter(x -> ((Collection<SettlementStatus>) a[0]).contains(x.getStatus())).toList();
                    case "findBySourceTypeOrderByCreatedAtDesc": return l.stream().filter(x -> x.getSourceType() == a[0]).toList();
                    case "findBySourceTypeAndStatusInOrderByCreatedAtDesc": return l.stream().filter(x -> x.getSourceType() == a[0] && ((Collection<SettlementStatus>) a[1]).contains(x.getStatus())).toList();
                    case "findByBusinessIdOrderByCreatedAtDesc": return l.stream().filter(x -> x.getBusiness().getId().equals(a[0])).toList();
                    case "sumNetForBusinessAndStatus": return l.stream().filter(x -> x.getBusiness().getId().equals(a[0]) && x.getStatus() == a[1]).map(RestaurantSettlement::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    case "totalsBySource": {
                        Map<SettlementSourceType, BigDecimal[]> m = new EnumMap<>(SettlementSourceType.class);
                        Map<SettlementSourceType, Long> c = new EnumMap<>(SettlementSourceType.class);
                        for (RestaurantSettlement x : l) {
                            if (x.getStatus() == SettlementStatus.CANCELLED) continue;
                            BigDecimal[] t = m.computeIfAbsent(x.getSourceType(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                            t[0] = t[0].add(x.getGrossAmount()); t[1] = t[1].add(x.getCommissionAmount()); t[2] = t[2].add(x.getPaymentFeeAmount());
                            t[3] = t[3].add(x.getAdjustmentAmount()); t[4] = t[4].add(x.getNetAmount()); t[5] = t[5].add(x.getRefundAmount());
                            c.merge(x.getSourceType(), 1L, Long::sum);
                        }
                        List<Object[]> rows = new ArrayList<>();
                        m.forEach((k, t) -> rows.add(new Object[] {k, c.get(k), t[0], t[1], t[2], t[3], t[4], t[5]}));
                        return rows;
                    }
                    case "totalsByStatus": {
                        Map<SettlementStatus, BigDecimal> m = new EnumMap<>(SettlementStatus.class);
                        Map<SettlementStatus, Long> c = new EnumMap<>(SettlementStatus.class);
                        for (RestaurantSettlement x : l) { m.merge(x.getStatus(), x.getNetAmount(), BigDecimal::add); c.merge(x.getStatus(), 1L, Long::sum); }
                        List<Object[]> rows = new ArrayList<>();
                        m.forEach((k, v) -> rows.add(new Object[] {k, c.get(k), v}));
                        return rows;
                    }
                    case "sumPulaLossAllSources": return l.stream().filter(x -> x.getType() == SettlementType.MERCHANT_PROTECTION && x.getStatus() != SettlementStatus.CANCELLED).map(RestaurantSettlement::getPulaFulfilmentLoss).reduce(BigDecimal.ZERO, BigDecimal::add);
                    case "totalsForBusiness": return List.of();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            SettlementAdjustmentRepository adjRepo = repo(SettlementAdjustmentRepository.class, adjustmentStore, (n, a, s) -> {
                List<SettlementAdjustment> l = all(s, SettlementAdjustment.class);
                switch (n) {
                    case "findBySettlementIdOrderByCreatedAtAsc": return l.stream().filter(x -> x.getSettlement().getId().equals(a[0])).toList();
                    case "findBySettlementIdAndIdempotencyKey": return l.stream().filter(x -> x.getSettlement().getId().equals(a[0]) && x.getIdempotencyKey().equals(a[1])).findFirst();
                    case "sumForSettlement": return l.stream().filter(x -> x.getSettlement().getId().equals(a[0])).map(SettlementAdjustment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    default: throw new UnsupportedOperationException(n);
                }
            });
            OrderRepository orderRepo = repo(OrderRepository.class, orderStore, (n, a, s) -> {
                if (n.equals("findRecentPaid")) return all(s, Order.class).stream().filter(o -> o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL).toList();
                throw new UnsupportedOperationException(n);
            });
            StockOrderRepository stockRepo = repo(StockOrderRepository.class, stockStore, (n, a, s) -> {
                if (n.equals("findRecentPaid")) return all(s, StockOrder.class).stream().filter(o -> o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL).toList();
                throw new UnsupportedOperationException(n);
            });
            ServiceBookingRepository bookingRepo = repo(ServiceBookingRepository.class, bookingStore, (n, a, s) -> {
                if (n.equals("findRecentPaid")) return all(s, ServiceBooking.class).stream().filter(o -> o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL).toList();
                throw new UnsupportedOperationException(n);
            });
            PaymentRefundRepository refundRepo = repo(PaymentRefundRepository.class, new HashMap<>(), (n, a, s) -> {
                if (n.equals("sumRefundedByOrder")) return refundedByOrder.getOrDefault((String) a[0], BigDecimal.ZERO);
                throw new UnsupportedOperationException(n);
            });
            BusinessRepository bizRepo = repo(BusinessRepository.class, businessStore, (n, a, s) -> {
                if (n.equals("findByOwnerId")) return Optional.ofNullable(businessByOwner.get((String) a[0]));
                throw new UnsupportedOperationException(n);
            });
            DriverPayoutRepository driverRepo = repo(DriverPayoutRepository.class, new HashMap<>(), (n, a, s) -> {
                if (n.equals("totalsByStatus")) return List.<Object[]>of(new Object[] {PayoutStatus.SUCCESSFUL, 2L, d("55.00")});
                throw new UnsupportedOperationException(n);
            });

            List<SettlementPolicy> policies = List.of(new RetailSettlementPolicy(orderRepo, refundRepo, props),
                    new StockSettlementPolicy(stockRepo, props), new ServiceSettlementPolicy(bookingRepo, props),
                    new FoodSettlementPolicy(orderRepo, refundRepo, foodProps));
            final World w = this;
            PayoutProvider provider = new PayoutProvider() {
                public String name() { return "fake"; }
                public boolean sandbox() { return false; }
                public PayoutResult pay(PayoutRequest r) {
                    w.payoutCalls.add(r);
                    return switch (w.mode) {
                        case SUCCESS -> new PayoutResult(PayoutStatus.SUCCESSFUL, "PAYOUT-" + w.payoutCalls.size(), null);
                        case FAILED -> new PayoutResult(PayoutStatus.FAILED, null, "Insufficient funds");
                        case PENDING -> new PayoutResult(PayoutStatus.PROCESSING, "PENDING-REF", null);
                        case THROW -> throw new IllegalStateException("timeout");
                    };
                }
            };
            PayoutOrchestrator orchestrator = new PayoutOrchestrator(provider) {
                @Override public PayoutProvider providerFor(Purpose purpose) { return provider; }
            };
            NotificationService notif = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { w.notes.add(r.getFullName() + ":" + m); }
            };
            AuditLogService audit = new AuditLogService(null) {
                @Override public void record(User actor, String action, String type, String id, String details) { w.audits.add(action); }
            };
            payoutService = new SettlementPayoutService(settlementRepo, orchestrator, notif, 3, 15, s ->
                    policies.stream().filter(p -> p.sourceType() == s.getSourceType()).findFirst().map(p -> p.facts(s.getSourceId()).earned()).orElse(false));
            engine = new MarketplaceSettlementService(policies, settlementRepo, adjRepo, payoutService, bizRepo, driverRepo, audit);
            reconciliation = new MarketplaceReconciliationService(policies, orderRepo, stockRepo, bookingRepo, settlementRepo);
            insights = new SettlementInsightService(settlementRepo, reconciliation);
        }

        static <E> List<E> all(Map<String, Object> s, Class<E> c) { return s.values().stream().map(c::cast).toList(); }

        User user(String name) { User u = new User(); u.setFullName(name); u.setEmail(name + "@x.test"); u.setPhoneNumber("27820000000"); return u; }

        Business business(String name) {
            User owner = user(name + "-owner");
            Business b = new Business();
            b.setOwner(owner); b.setName(name); b.setActive(true); b.setCreatedAt(Instant.now());
            businessStore.put(b.getId(), b);
            businessByOwner.put(owner.getId(), b);
            return b;
        }

        Payment payment(String amount, String currency, boolean confirmed) {
            Payment p = new Payment();
            p.setAmount(d(amount)); p.setCurrency(currency); p.setProviderReference("REF-" + p.getId().substring(0, 8));
            if (confirmed) { p.transitionTo(PaymentStatus.PROCESSING); p.transitionTo(PaymentStatus.SUCCESSFUL); }
            return p;
        }

        Order retailOrder(Business seller, String subtotal, String total, OrderStatus status, boolean paid) {
            Order o = new Order();
            o.setBusiness(seller); o.setSubtotal(d(subtotal)); o.setTotal(d(total)); o.setCurrency("ZAR"); o.setStatus(status);
            o.setPayment(payment(total, "ZAR", paid)); o.setCreatedAt(Instant.now());
            orderStore.put(o.getId(), o);
            return o;
        }

        Order foodOrder(Business seller, String subtotal, String total, OrderStatus status) {
            Order o = retailOrder(seller, subtotal, total, status, true);
            o.setKind(OrderKind.FOOD);
            return o;
        }

        StockOrder stockOrder(Business supplier, String subtotal, String total, StockOrderStatus status) {
            StockOrder o = new StockOrder();
            o.setSupplierBusiness(supplier); o.setBuyerBusiness(business("Buyer-" + o.getId().substring(0, 4)));
            o.setSubtotal(d(subtotal)); o.setTotal(d(total)); o.setCurrency("ZAR"); o.setStatus(status);
            o.setPayment(payment(total, "ZAR", true)); o.setCreatedAt(Instant.now());
            stockStore.put(o.getId(), o);
            return o;
        }

        ServiceBooking booking(Business provider, String total, ServiceBookingStatus status, boolean paid) {
            ServiceBooking b = new ServiceBooking();
            b.setBusiness(provider); b.setTotal(d(total)); b.setCurrency("ZAR"); b.setStatus(status);
            b.setPayment(payment(total, "ZAR", paid)); b.setCreatedAt(Instant.now());
            bookingStore.put(b.getId(), b);
            return b;
        }

        RestaurantSettlement row(SettlementSourceType t, String id) { return settlementRepo.findBySourceTypeAndSourceId(t, id).orElse(null); }
        int count() { return settlementStore.size(); }
    }

    // ================================================================== retail

    @Test
    void retailSettlementOpensAtPaymentBecomesEligibleOnlyWhenCompletedAndIsPaidOnce() {
        World w = new World();
        Business shop = w.business("Shop");
        Order o = w.retailOrder(shop, "1000.00", "1050.00", OrderStatus.PAID, true);     // R50 delivery is not seller revenue

        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(SettlementStatus.NOT_ELIGIBLE, s.getStatus());                       // payment alone does not make it payable
        assertEquals("settlement:RETAIL_ORDER:" + o.getId(), s.getIdempotencyKey());      // deterministic key
        assertEquals(SellerKind.RETAIL_BUSINESS, s.getSellerKind());
        assertEquals(d("1000.00"), s.getGrossAmount());                                   // delivery fee excluded
        assertEquals(d("100.00"), s.getCommissionAmount());                               // 10%
        assertEquals(d("2.00"), s.getOtherFees());
        assertEquals(d("15.50"), s.getPaymentFeeAmount());                                // 1.5% of 1000 + 0.50
        assertEquals(d("882.50"), s.getNetAmount());
    }

    @Test
    void completionMakesRetailEligibleAfterTheDisputeWindowAndPayoutHappensExactlyOnce() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "500.00", "500.00", OrderStatus.PAID, true);
        w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        // not complete yet: onEarned does nothing
        assertEquals(SettlementStatus.NOT_ELIGIBLE, w.engine.onEarned(SettlementSourceType.RETAIL_ORDER, o.getId()).getStatus());
        assertTrue(w.engine.dueForPayout().isEmpty());

        o.setStatus(OrderStatus.COMPLETED);
        RestaurantSettlement s = w.engine.onEarned(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertTrue(s.getEligibleAt().isAfter(Instant.now().plus(Duration.ofMinutes(58))));  // 60 minute dispute window
        assertTrue(w.engine.dueForPayout().isEmpty());                                      // window not over: not payable yet

        set(s, RestaurantSettlement.class, "eligibleAt", Instant.now().minusSeconds(5));
        assertEquals(1, w.engine.dueForPayout().size());
        assertTrue(w.engine.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals("PAYOUT-1", s.getPayoutReference());
        assertEquals("payout:settlement:" + s.getId(), w.payoutCalls.get(0).idempotencyKey());
        assertEquals(s.getNetAmount(), w.payoutCalls.get(0).amount());
        // paying again (duplicate run / second scheduler) sends nothing
        assertFalse(w.engine.pay(s.getId(), false));
        assertEquals(1, w.payoutCalls.size());
        assertTrue(w.engine.dueForPayout().isEmpty());
        assertTrue(w.engine.onEarned(SettlementSourceType.RETAIL_ORDER, o.getId()).getStatus() == SettlementStatus.PAID);
    }

    @Test
    void duplicateWebhookOrRetryNeverCreatesASecondSettlement() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "300.00", "300.00", OrderStatus.PAID, true);
        RestaurantSettlement a = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        RestaurantSettlement b = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        RestaurantSettlement c = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertSame(a, b);
        assertSame(a, c);
        assertEquals(1, w.count());
    }

    @Test
    void settlementHooksNeverThrowIntoThePaymentTransactionAndAreWiredThroughTheSafeVariants() throws Exception {
        World w = new World();
        // a missing/odd source is logged and swallowed by the safe hooks; the strict methods still throw
        assertDoesNotThrow(() -> w.engine.openSafely(SettlementSourceType.RETAIL_ORDER, "missing"));
        assertDoesNotThrow(() -> w.engine.onEarnedSafely(SettlementSourceType.STOCK_ORDER, "missing"));
        expect("NOT_FOUND", () -> w.engine.open(SettlementSourceType.RETAIL_ORDER, "missing"));
        // and they still do the work when everything is fine
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        w.engine.openSafely(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(SettlementStatus.ELIGIBLE, w.row(SettlementSourceType.RETAIL_ORDER, o.getId()).getStatus());
        // the production hooks call the safe variants, never the strict ones
        for (String f : new String[] {"OrderService", "StockPaymentService", "StockOrderService", "ServiceBookingService"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/java/com/momocircle/service/" + f + ".java"));
            assertTrue(src.contains("marketplaceSettlement.openSafely") || src.contains("marketplaceSettlement.onEarnedSafely"), f + " must use the safe settlement hooks");
            assertFalse(src.contains("marketplaceSettlement.open(") || src.contains("marketplaceSettlement.onEarned("), f + " must not call the strict hooks from the payment path");
        }
    }

    @Test
    void anUnpaidOrCancelledSaleOpensNothing() {
        World w = new World();
        Business shop = w.business("Shop");
        Order unpaid = w.retailOrder(shop, "100.00", "100.00", OrderStatus.PENDING_PAYMENT, false);
        assertNull(w.engine.open(SettlementSourceType.RETAIL_ORDER, unpaid.getId()));
        Order cancelled = w.retailOrder(shop, "100.00", "100.00", OrderStatus.CANCELLED, true);
        assertNull(w.engine.open(SettlementSourceType.RETAIL_ORDER, cancelled.getId()));
        assertEquals(0, w.count());
        expect("NOT_FOUND", () -> w.engine.open(SettlementSourceType.RETAIL_ORDER, "missing"));
        // the wrong policy is refused: a food order is not a retail settlement
        Order food = w.foodOrder(shop, "100.00", "100.00", OrderStatus.PAID);
        expect("BAD_REQUEST", () -> w.engine.open(SettlementSourceType.RETAIL_ORDER, food.getId()));
    }

    @Test
    void partialRefundIsDeductedBeforePayoutAndFrozenAfter() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "400.00", "400.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());   // paid + completed -> eligible
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        BigDecimal before = s.getNetAmount();
        w.refundedByOrder.put(o.getId(), d("100.00"));
        w.engine.onRefundChanged(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(d("100.00"), s.getRefundAmount());
        assertEquals(d("300.00"), s.getEligibleRevenue());
        assertTrue(s.getNetAmount().compareTo(before) < 0);
        // after the payout the record is history: a later refund does not rewrite it
        set(s, RestaurantSettlement.class, "eligibleAt", Instant.now().minusSeconds(1));
        w.engine.pay(s.getId(), false);
        BigDecimal paid = s.getNetAmount();
        w.refundedByOrder.put(o.getId(), d("250.00"));
        w.engine.onRefundChanged(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(paid, s.getNetAmount());
        assertEquals(d("100.00"), s.getRefundAmount());
    }

    @Test
    void cancellationCancelsAnOpenSettlementButNeverAPaidOne() {
        World w = new World();
        Order a = w.retailOrder(w.business("A"), "100.00", "100.00", OrderStatus.PAID, true);
        RestaurantSettlement sa = w.engine.open(SettlementSourceType.RETAIL_ORDER, a.getId());
        assertTrue(w.engine.onCancelled(SettlementSourceType.RETAIL_ORDER, a.getId()));
        assertEquals(SettlementStatus.CANCELLED, sa.getStatus());
        assertEquals(d("0.00"), sa.getNetAmount());
        assertFalse(w.engine.pay(sa.getId(), false));                                  // a cancelled settlement is never payable
        assertTrue(w.payoutCalls.isEmpty());

        Order b = w.retailOrder(w.business("B"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement sb = w.engine.open(SettlementSourceType.RETAIL_ORDER, b.getId());
        w.engine.pay(sb.getId(), false);
        assertEquals(SettlementStatus.PAID, sb.getStatus());
        assertFalse(w.engine.onCancelled(SettlementSourceType.RETAIL_ORDER, b.getId()));  // already paid: needs a human
        assertEquals(SettlementStatus.PAID, sb.getStatus());
        assertTrue(w.engine.onCancelled(SettlementSourceType.RETAIL_ORDER, "never-settled"));
    }

    // ================================================================== stock

    @Test
    void stockSupplierIsSettledOnDeliveryWithoutTheDeliveryFee() {
        World w = new World();
        Business supplier = w.business("Wholesaler");
        StockOrder o = w.stockOrder(supplier, "2000.00", "2150.00", StockOrderStatus.PAID);   // R150 delivery
        RestaurantSettlement s = w.engine.open(SettlementSourceType.STOCK_ORDER, o.getId());
        assertEquals(SellerKind.SUPPLIER, s.getSellerKind());
        assertEquals(supplier.getId(), s.getBusiness().getId());
        assertEquals(d("2000.00"), s.getGrossAmount());
        assertEquals(d("100.00"), s.getCommissionAmount());                                  // 5%
        assertEquals(SettlementStatus.NOT_ELIGIBLE, s.getStatus());
        assertNull(s.getOrder());                                                            // not a consumer order
        o.setStatus(StockOrderStatus.DELIVERED);
        assertEquals(SettlementStatus.ELIGIBLE, w.engine.onEarned(SettlementSourceType.STOCK_ORDER, o.getId()).getStatus());
        assertTrue(w.engine.pay(s.getId(), false));
        assertEquals(d("1900.00"), w.payoutCalls.get(0).amount());
    }

    // ================================================================== services

    @Test
    void serviceProviderIsSettledOnlyWhenTheJobIsCompleteAndPaid() {
        World w = new World();
        Business provider = w.business("Plumber");
        ServiceBooking inProgress = w.booking(provider, "800.00", ServiceBookingStatus.IN_PROGRESS, true);
        RestaurantSettlement notYet = w.engine.open(SettlementSourceType.SERVICE_BOOKING, inProgress.getId());
        assertEquals(SettlementStatus.NOT_ELIGIBLE, notYet.getStatus());
        inProgress.setStatus(ServiceBookingStatus.COMPLETED);
        assertEquals(SettlementStatus.ELIGIBLE, w.engine.onEarned(SettlementSourceType.SERVICE_BOOKING, inProgress.getId()).getStatus());

        // payment is due after completion: a completed but unpaid job opens nothing
        ServiceBooking unpaid = w.booking(provider, "500.00", ServiceBookingStatus.COMPLETED, false);
        assertNull(w.engine.open(SettlementSourceType.SERVICE_BOOKING, unpaid.getId()));
        // a completed job that is paid becomes eligible straight away
        ServiceBooking done = w.booking(provider, "500.00", ServiceBookingStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.SERVICE_BOOKING, done.getId());
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertEquals(SellerKind.SERVICE_PROVIDER, s.getSellerKind());
        assertEquals(d("60.00"), s.getCommissionAmount());                                   // 12%
        assertEquals(d("440.00"), s.getNetAmount());
    }

    // ================================================================== food policy through the shared engine

    @Test
    void foodUsesItsOwnConfigurationAndKeepsItsLegacyKey() {
        World w = new World();
        Order o = w.foodOrder(w.business("Cafe"), "200.00", "230.00", OrderStatus.PAID);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.FOOD_ORDER, o.getId());
        assertEquals("settlement:" + o.getId(), s.getIdempotencyKey());                      // same key the food service always used
        assertEquals(SellerKind.RESTAURANT, s.getSellerKind());
        assertEquals(d("30.00"), s.getCommissionAmount());                                   // app.food.settlement rate, not marketplace
        assertEquals(d("170.00"), s.getNetAmount());
        assertSame(s, w.engine.open(SettlementSourceType.FOOD_ORDER, o.getId()));
    }

    // ================================================================== payouts: failure, uncertainty, retries

    @Test
    void failedPayoutIsRecordedAndRetriedWithTheSameIdempotencyKeyUntilTheCeiling() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        w.mode = World.PayoutMode.FAILED;
        assertFalse(w.engine.pay(s.getId(), false));
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals("Insufficient funds", s.getFailureReason());
        assertEquals(1, s.getPayoutAttempts());
        assertTrue(w.engine.dueForPayout().stream().anyMatch(x -> x.settlementId().equals(s.getId())));  // retryable
        w.mode = World.PayoutMode.SUCCESS;
        assertTrue(w.engine.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(2, s.getPayoutAttempts());
        assertEquals(w.payoutCalls.get(0).idempotencyKey(), w.payoutCalls.get(1).idempotencyKey());      // same key: provider de-duplicates

        // the attempt ceiling stops endless retries
        Order o2 = w.retailOrder(w.business("Shop2"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s2 = w.engine.open(SettlementSourceType.RETAIL_ORDER, o2.getId());
        w.mode = World.PayoutMode.FAILED;
        for (int i = 0; i < 3; i++) w.engine.pay(s2.getId(), false);
        assertEquals(3, s2.getPayoutAttempts());
        assertTrue(w.engine.dueForPayout().stream().noneMatch(x -> x.settlementId().equals(s2.getId())));
        assertEquals(SettlementStatus.FAILED, s2.getStatus());
    }

    @Test
    void anUnknownPayoutOutcomeIsNeverMarkedPaidOrFailedAndIsSafelyRedriven() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        w.mode = World.PayoutMode.THROW;
        assertFalse(w.engine.pay(s.getId(), false));
        assertEquals(SettlementStatus.PROCESSING, s.getStatus());                          // neither PAID nor FAILED
        assertNull(s.getPaidAt());
        assertTrue(s.getFailureReason().startsWith("Payout call errored"));
        assertTrue(w.engine.dueForPayout().isEmpty());                                      // not stuck yet
        set(s, BaseEntity.class, "updatedAt", Instant.now().minus(Duration.ofMinutes(30)));
        var due = w.engine.dueForPayout();
        assertEquals(1, due.size());
        assertTrue(due.get(0).stuck());
        w.mode = World.PayoutMode.SUCCESS;
        assertTrue(w.engine.pay(s.getId(), true));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(w.payoutCalls.get(0).idempotencyKey(), w.payoutCalls.get(1).idempotencyKey());
        // a provider that answers "still processing" does not make it PAID either
        Order o2 = w.retailOrder(w.business("Shop2"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s2 = w.engine.open(SettlementSourceType.RETAIL_ORDER, o2.getId());
        w.mode = World.PayoutMode.PENDING;
        assertFalse(w.engine.pay(s2.getId(), false));
        assertEquals(SettlementStatus.PROCESSING, s2.getStatus());
    }

    @Test
    void aZeroNetSettlementClosesWithoutCallingTheProvider() {
        World w = new World();
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        w.refundedByOrder.put(o.getId(), d("100.00"));
        w.engine.onRefundChanged(SettlementSourceType.RETAIL_ORDER, o.getId());
        assertEquals(d("0.00"), s.getNetAmount());
        assertTrue(w.engine.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertTrue(w.payoutCalls.isEmpty());
    }

    // ================================================================== admin: hold, release, adjustments

    @Test
    void heldSettlementsAreNotPaidUntilReleased() {
        World w = new World();
        User admin = w.user("Admin");
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        SettlementView held = w.engine.hold(admin, s.getId(), "fraud review");
        assertEquals(SettlementStatus.ON_HOLD, held.status());
        assertFalse(w.engine.pay(s.getId(), false));
        assertTrue(w.payoutCalls.isEmpty());
        assertEquals(SettlementStatus.ELIGIBLE, w.engine.release(admin, s.getId()).status());   // earned -> eligible again
        assertTrue(w.engine.pay(s.getId(), false));
        expect("CONFLICT", () -> w.engine.hold(admin, s.getId(), "too late"));                   // paid: cannot be held
        expect("CONFLICT", () -> w.engine.release(admin, s.getId()));
        assertTrue(w.audits.contains("SETTLEMENT_HELD") && w.audits.contains("SETTLEMENT_RELEASED"));
    }

    @Test
    void adjustmentsAreImmutableIdempotentAndOnlyBeforePayout() {
        World w = new World();
        User admin = w.user("Admin");
        Order o = w.retailOrder(w.business("Shop"), "1000.00", "1000.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        BigDecimal base = s.getNetAmount();

        SettlementView v1 = w.engine.adjust(admin, s.getId(), AdjustmentKind.DEBIT, d("40.00"), "Damaged goods credit to customer", "adj-1");
        assertEquals(d("-40.00"), v1.adjustments());
        assertEquals(base.subtract(d("40.00")), v1.net());
        assertEquals(1, v1.adjustmentHistory().size());
        // replay with the same key changes nothing
        SettlementView again = w.engine.adjust(admin, s.getId(), AdjustmentKind.DEBIT, d("40.00"), "Damaged goods credit to customer", "adj-1");
        assertEquals(1, again.adjustmentHistory().size());
        assertEquals(v1.net(), again.net());
        // the same key with a different payload is refused (no silent rewrite of history)
        expect("CONFLICT", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("40.00"), "x", "adj-1"));
        expect("CONFLICT", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.DEBIT, d("41.00"), "x", "adj-1"));
        // a credit is a NEW row; the old row stays
        SettlementView v2 = w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("10.00"), "Goodwill", "adj-2");
        assertEquals(d("-30.00"), v2.adjustments());
        assertEquals(2, v2.adjustmentHistory().size());
        assertEquals(d("-40.00"), v2.adjustmentHistory().get(0).amount());                      // untouched
        // validation
        expect("BAD_REQUEST", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("0.00"), "x", "k"));
        expect("BAD_REQUEST", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("5.00"), " ", "k"));
        expect("BAD_REQUEST", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("5.00"), "x", " "));
        expect("NOT_FOUND", () -> w.engine.adjust(admin, "missing", AdjustmentKind.CREDIT, d("5.00"), "x", "k"));
        // a huge debit cannot make the payout negative
        assertEquals(d("0.00"), w.engine.adjust(admin, s.getId(), AdjustmentKind.DEBIT, d("999999.00"), "Clawback", "adj-3").net());

        // once the money moved, the record is history
        w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("999999.00"), "undo", "adj-4");
        assertTrue(w.engine.pay(s.getId(), false));
        expect("CONFLICT", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.CREDIT, d("1.00"), "late", "adj-5"));
        assertEquals(4, w.adjustmentStore.size());
        assertTrue(w.audits.contains("SETTLEMENT_ADJUSTED"));
    }

    @Test
    void adjustmentIsRefusedOnceAPayoutIsInFlight() {
        World w = new World();
        User admin = w.user("Admin");
        Order o = w.retailOrder(w.business("Shop"), "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        w.mode = World.PayoutMode.THROW;
        w.engine.pay(s.getId(), false);                       // now PROCESSING with an unknown outcome
        expect("CONFLICT", () -> w.engine.adjust(admin, s.getId(), AdjustmentKind.DEBIT, d("1.00"), "x", "k1"));
        expect("CONFLICT", () -> w.engine.hold(admin, s.getId(), "x"));
    }

    // ================================================================== security: seller isolation & admin-only surface

    @Test
    void sellersSeeOnlyTheirOwnSettlements() {
        World w = new World();
        Business a = w.business("Alpha");
        Business b = w.business("Beta");
        Order oa = w.retailOrder(a, "100.00", "100.00", OrderStatus.COMPLETED, true);
        Order ob = w.retailOrder(b, "200.00", "200.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement sa = w.engine.open(SettlementSourceType.RETAIL_ORDER, oa.getId());
        RestaurantSettlement sb = w.engine.open(SettlementSourceType.RETAIL_ORDER, ob.getId());
        assertEquals(1, w.engine.mine(a.getOwner(), 50).size());
        assertEquals(sa.getId(), w.engine.mine(a.getOwner(), 50).get(0).id());
        assertEquals(sa.getId(), w.engine.mineOne(a.getOwner(), sa.getId()).id());
        expect("NOT_FOUND", () -> w.engine.mineOne(a.getOwner(), sb.getId()));                   // ID manipulation: another seller's id
        expect("NOT_FOUND", () -> w.engine.mineOne(w.user("Stranger"), sa.getId()));
        expect("NOT_FOUND", () -> w.engine.mineOne(a.getOwner(), "nope"));
        assertTrue(w.engine.mine(w.user("NoBusiness"), 50).isEmpty());
        // seller explanations are scoped the same way
        assertNotNull(w.insights.explainSettlement(a.getOwner(), false, sa.getId()));
        expect("NOT_FOUND", () -> w.insights.explainSettlement(a.getOwner(), false, sb.getId()));
        assertNotNull(w.insights.explainSettlement(w.user("Admin"), true, sb.getId()));
    }

    @Test
    void theAdminSurfaceHasNoWayToMarkPaidRefundOrOverrideReconciliation() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/java/com/momocircle/controller/AdminMarketplaceFinanceController.java"));
        // every handler checks for an admin
        long handlers = src.lines().filter(l -> l.contains("@GetMapping") || l.contains("@PostMapping") || l.contains("@PutMapping") || l.contains("@DeleteMapping")).count();
        long guards = java.util.regex.Pattern.compile("requireAdmin\\(\\)").matcher(src).results().count();
        assertTrue(handlers >= 9 && guards >= handlers, "every admin endpoint must call requireAdmin()");
        assertFalse(src.contains("@PutMapping") && src.toLowerCase().contains("markpaid"));
        for (String forbidden : new String[] {"markPaid", "setStatus", "transitionTo", "refund(", "overrideReconc", "setNetAmount"}) {
            assertFalse(src.contains(forbidden), "admin controller must not contain " + forbidden);
        }
        String seller = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/java/com/momocircle/controller/MarketplaceSettlementController.java"));
        assertFalse(seller.contains("@PostMapping") || seller.contains("@PutMapping") || seller.contains("@DeleteMapping"), "sellers get read-only access");
    }

    // ================================================================== reconciliation

    @Test
    void reconciliationFindsEveryKindOfDiscrepancy() {
        World w = new World();
        Business shop = w.business("Shop");

        Order good = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        w.engine.open(SettlementSourceType.RETAIL_ORDER, good.getId());
        assertEquals(ReconciliationState.MATCHED, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, good.getId()).state());

        Order noSettlement = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        assertEquals(ReconciliationState.MISSING_SETTLEMENT, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, noSettlement.getId()).state());

        Order amount = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        amount.getPayment().setAmount(d("90.00"));
        assertEquals(ReconciliationState.AMOUNT_MISMATCH, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, amount.getId()).state());

        Order currency = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        currency.getPayment().setCurrency("USD");
        assertEquals(ReconciliationState.REFERENCE_MISMATCH, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, currency.getId()).state());

        Order grossDrift = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement drift = w.engine.open(SettlementSourceType.RETAIL_ORDER, grossDrift.getId());
        drift.setGrossAmount(d("120.00"));
        var driftRow = w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, grossDrift.getId());
        assertEquals(ReconciliationState.AMOUNT_MISMATCH, driftRow.state());
        assertEquals(d("120.00"), driftRow.settlementGross());
        assertEquals(drift.getId(), driftRow.settlementId());

        Order wrongPayment = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement wp = w.engine.open(SettlementSourceType.RETAIL_ORDER, wrongPayment.getId());
        wp.setPayment(w.payment("100.00", "ZAR", true));
        assertEquals(ReconciliationState.REFERENCE_MISMATCH, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, wrongPayment.getId()).state());

        Order paidNoRef = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement nr = w.engine.open(SettlementSourceType.RETAIL_ORDER, paidNoRef.getId());
        w.engine.pay(nr.getId(), false);
        nr.setPayoutReference(null);
        assertEquals(ReconciliationState.MISSING_PAYOUT, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, paidNoRef.getId()).state());

        Order lostPayment = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        w.engine.open(SettlementSourceType.RETAIL_ORDER, lostPayment.getId());
        lostPayment.getPayment().transitionTo(PaymentStatus.REFUNDED);                    // payment no longer confirmed
        var lost = w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, lostPayment.getId());
        assertEquals(ReconciliationState.MISSING_PAYMENT, lost.state());

        Order cancelledAfter = w.retailOrder(shop, "100.00", "100.00", OrderStatus.COMPLETED, true);
        w.engine.open(SettlementSourceType.RETAIL_ORDER, cancelledAfter.getId());
        cancelledAfter.setStatus(OrderStatus.CANCELLED);
        assertEquals(ReconciliationState.MISMATCH, w.reconciliation.reconcileOne(SettlementSourceType.RETAIL_ORDER, cancelledAfter.getId()).state());

        // whole-marketplace report: counts and rows, covering every domain; it changes nothing
        StockOrder st = w.stockOrder(w.business("Sup"), "100.00", "100.00", StockOrderStatus.DELIVERED);
        ServiceBooking sv = w.booking(w.business("Pro"), "100.00", ServiceBookingStatus.COMPLETED, true);
        int before = w.settlementStore.size();
        ReconciliationReport report = w.reconciliation.reconcile(100);
        assertEquals(before, w.settlementStore.size());
        assertTrue(report.checked() >= 10);
        assertTrue(report.rows().stream().anyMatch(r -> r.sourceType() == SettlementSourceType.STOCK_ORDER && r.state() == ReconciliationState.MISSING_SETTLEMENT && r.sourceId().equals(st.getId())));
        assertTrue(report.rows().stream().anyMatch(r -> r.sourceType() == SettlementSourceType.SERVICE_BOOKING && r.state() == ReconciliationState.MISSING_SETTLEMENT && r.sourceId().equals(sv.getId())));
        assertEquals(report.checked(), report.matched() + report.problems());
        // the explainer reports the same facts without changing anything
        InsightsView explained = w.insights.explainReconciliation(SettlementSourceType.RETAIL_ORDER, noSettlement.getId());
        assertTrue(explained.summary().contains("MISSING_SETTLEMENT"));
        assertTrue(explained.notice().contains("cannot change a settlement"));
    }

    // ================================================================== reporting (Cash Flow backbone: reads, no ledger)

    @Test
    void marketplaceSummaryBreaksOutEveryComponentSeparately() {
        World w = new World();
        Order r = w.retailOrder(w.business("Shop"), "1000.00", "1000.00", OrderStatus.COMPLETED, true);
        w.engine.open(SettlementSourceType.RETAIL_ORDER, r.getId());
        StockOrder s = w.stockOrder(w.business("Sup"), "2000.00", "2000.00", StockOrderStatus.DELIVERED);
        w.engine.open(SettlementSourceType.STOCK_ORDER, s.getId());
        ServiceBooking b = w.booking(w.business("Pro"), "500.00", ServiceBookingStatus.COMPLETED, true);
        w.engine.open(SettlementSourceType.SERVICE_BOOKING, b.getId());
        // a merchant-protection compensation: customer refund, restaurant compensation, driver payout and PULA loss stay separate
        RestaurantSettlement mp = new RestaurantSettlement();
        mp.setType(SettlementType.MERCHANT_PROTECTION);
        mp.setBusiness(w.business("Cafe"));
        mp.setSourceType(SettlementSourceType.FOOD_ORDER);
        mp.setSourceId("food-1");
        mp.setGrossAmount(d("0.00"));
        mp.setNetAmount(d("80.00"));
        mp.setPulaFulfilmentLoss(d("80.00"));
        mp.setCreatedAt(Instant.now());
        w.settlementStore.put(mp.getId(), mp);

        MarketplaceSummary sum = w.engine.summary();
        assertEquals(d("3500.00"), sum.grossMarketplaceVolume());
        assertEquals(d("100.00").add(d("100.00")).add(d("60.00")), sum.pulaCommissionRevenue());       // 10% + 5% + 12%
        assertEquals(d("15.50"), sum.paymentFees());                                                   // only retail has a payment fee configured
        assertEquals(d("80.00"), sum.pulaOperationalLoss());
        assertEquals(4, sum.bySource().size());
        assertEquals(1, sum.driverPayouts().size());
        assertEquals(d("55.00"), sum.driverPayouts().get(0).amount());                                  // reused DriverPayout data, not a new table
        assertTrue(sum.bySource().stream().anyMatch(t -> t.sourceType() == SettlementSourceType.STOCK_ORDER && t.gross().compareTo(d("2000.00")) == 0));
    }

    @Test
    void settlementExplanationShowsEveryComponent() {
        World w = new World();
        Business shop = w.business("Shop");
        Order o = w.retailOrder(shop, "1000.00", "1000.00", OrderStatus.COMPLETED, true);
        RestaurantSettlement s = w.engine.open(SettlementSourceType.RETAIL_ORDER, o.getId());
        InsightsView v = w.insights.explainSettlement(shop.getOwner(), false, s.getId());
        String text = String.join("\n", v.points());
        assertTrue(text.contains("Gross sales: ZAR 1000.00"));
        assertTrue(text.contains("Commission: ZAR 100.00 at 10%"));
        assertTrue(text.contains("Payment fee: ZAR 15.50"));
        assertTrue(text.contains("You receive: ZAR " + s.getNetAmount()));
        assertTrue(v.notice().contains("cannot change a settlement, approve a payout, issue a refund or override a reconciliation"));
    }
}
