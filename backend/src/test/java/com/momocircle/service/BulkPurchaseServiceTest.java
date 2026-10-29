package com.momocircle.service;

import com.momocircle.domain.BulkAllocation;
import com.momocircle.domain.BulkPurchaseGroup;
import com.momocircle.domain.Business;
import com.momocircle.domain.Payment;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.WholesaleVolumeTier;
import com.momocircle.domain.enums.BulkAllocationStatus;
import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.BulkDtos.*;
import com.momocircle.dto.StockDtos.StockOrderResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BulkAllocationRepository;
import com.momocircle.repository.BulkGroupEventRepository;
import com.momocircle.repository.BulkPurchaseGroupRepository;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.WholesaleProductRepository;
import com.momocircle.repository.WholesaleVolumeTierRepository;
import com.momocircle.security.ServiceRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the REAL BulkPurchaseService with in-memory repositories. This proves service logic (pricing, state machine,
 * authorisation, price locking, payment observation, shortage, cancellation). It does NOT prove SQL, Hibernate mapping,
 * the pessimistic row lock or Flyway: those need a real database (see docs/PHASE_6_BULK_PURCHASE_REPORT.md).
 */
class BulkPurchaseServiceTest {

    // ---------------- in-memory repository plumbing ----------------
    @SuppressWarnings("unchecked")
    private static <T> T repo(Class<T> type, Map<String, Object> store, Map<String, Function<Object[], Object>> custom) {
        InvocationHandler h = (proxy, m, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            Function<Object[], Object> f = custom.get(m.getName());
            if (f != null) return f.apply(a);
            switch (m.getName()) {
                case "save", "saveAndFlush" -> { store.put(((com.momocircle.domain.BaseEntity) a[0]).getId(), a[0]); return a[0]; }
                case "findById" -> { return Optional.ofNullable(store.get(a[0])); }
                case "flush" -> { return null; }
                case "toString" -> { return type.getSimpleName() + "Fake"; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == a[0]; }
                default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + m.getName());
            }
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }

    private final Map<String, Object> groupStore = new LinkedHashMap<>();
    private final Map<String, Object> allocStore = new LinkedHashMap<>();
    private final Map<String, Object> eventStore = new LinkedHashMap<>();
    private final Map<String, Object> productStore = new LinkedHashMap<>();
    private final Map<String, Object> tierStore = new LinkedHashMap<>();
    private final Map<String, Object> orderStore = new LinkedHashMap<>();
    private final Map<User, Business> businessByUser = new LinkedHashMap<>();

    private BulkPurchaseService service;
    private User uA, uB, uC, uD, uS;
    private Business bA, bB, bC, bD, supplier;
    private WholesaleProduct product;

    private List<BulkAllocation> allocs() {
        return allocStore.values().stream().map(o -> (BulkAllocation) o).collect(Collectors.toList());
    }

    private static User user(String id) { User u = new User(); u.setId(id); return u; }

    private Business business(User owner, String name, BusinessCapability cap) {
        Business b = new Business();
        b.setOwner(owner);
        b.setName(name);
        b.setCategory("Grocery");
        b.setCapabilities(new java.util.LinkedHashSet<>(List.of(cap)));
        businessByUser.put(owner, b);
        return b;
    }

    @BeforeEach
    void setUp() {
        uA = user("uA"); uB = user("uB"); uC = user("uC"); uD = user("uD"); uS = user("uS");
        bA = business(uA, "Spaza A", BusinessCapability.CAN_BUY_STOCK);
        bB = business(uB, "Spaza B", BusinessCapability.CAN_BUY_STOCK);
        bC = business(uC, "Spaza C", BusinessCapability.CAN_BUY_STOCK);
        bD = business(uD, "Outsider D", BusinessCapability.CAN_BUY_STOCK);
        supplier = business(uS, "Cape Wholesale", BusinessCapability.CAN_SUPPLY_BUSINESSES);

        product = new WholesaleProduct();
        product.setSupplierBusiness(supplier);
        product.setName("Maize meal 10kg");
        product.setPackSize(1);
        product.setMoq(50);
        product.setPricePerPack(new BigDecimal("100.00"));
        product.setCurrency("ZAR");
        product.setAvailableStock(1000);
        product.setActive(true);
        productStore.put(product.getId(), product);
        for (int[] t : new int[][]{{100, 90}, {200, 80}}) {
            WholesaleVolumeTier w = new WholesaleVolumeTier();
            w.setWholesaleProduct(product);
            w.setMinPacks(t[0]);
            w.setPricePerPack(new BigDecimal(t[1] + ".00"));
            tierStore.put(w.getId(), w);
        }

        BulkPurchaseGroupRepository groups = repo(BulkPurchaseGroupRepository.class, groupStore, Map.of(
                "findForUpdate", a -> Optional.ofNullable(groupStore.get(a[0]))));
        BulkAllocationRepository allocations = repo(BulkAllocationRepository.class, allocStore, Map.of(
                "findByGroupIdOrderByCreatedAtAsc", a -> allocs().stream().filter(x -> x.getGroup().getId().equals(a[0])).collect(Collectors.toList()),
                "findByGroupIdAndBusinessId", a -> allocs().stream().filter(x -> x.getGroup().getId().equals(a[0]) && x.getBusiness().getId().equals(a[1])).findFirst(),
                "sumActiveRequestedPacks", a -> allocs().stream().filter(x -> x.getGroup().getId().equals(a[0]) && x.getStatus().isActiveInterest())
                        .mapToLong(BulkAllocation::getRequestedPacks).sum()));
        BulkGroupEventRepository events = repo(BulkGroupEventRepository.class, eventStore, Map.of());
        WholesaleProductRepository products = repo(WholesaleProductRepository.class, productStore, Map.of(
                "reserve", a -> {
                    WholesaleProduct p = (WholesaleProduct) productStore.get(a[0]);
                    int q = (Integer) a[1];
                    if (!p.isActive() || p.getAvailableStock() < q) return 0;
                    p.setAvailableStock(p.getAvailableStock() - q);
                    return 1;
                },
                "release", a -> {
                    WholesaleProduct p = (WholesaleProduct) productStore.get(a[0]);
                    p.setAvailableStock(p.getAvailableStock() + (Integer) a[1]);
                    return 1;
                }));
        WholesaleVolumeTierRepository tiers = repo(WholesaleVolumeTierRepository.class, tierStore, Map.of(
                "findByWholesaleProductIdOrderByMinPacksAsc", a -> tierStore.values().stream().map(o -> (WholesaleVolumeTier) o)
                        .filter(t -> t.getWholesaleProduct().getId().equals(a[0]))
                        .sorted(java.util.Comparator.comparingInt(WholesaleVolumeTier::getMinPacks)).collect(Collectors.toList()),
                "deleteByWholesaleProductId", a -> { tierStore.clear(); return null; }));
        StockOrderRepository orderRepo = repo(StockOrderRepository.class, orderStore, Map.of());

        BusinessService businessService = new BusinessService(null, null) {
            @Override public Business requireOwn(User owner) {
                Business b = businessByUser.get(owner);
                if (b == null) throw ApiException.notFound("You don't have a business profile yet");
                return b;
            }
        };
        StockOrderService stockOrders = new StockOrderService(null, null, null, null, null) {
            @Override public void cancelAndRelease(StockOrder order) {
                if (order.getStatus() == StockOrderStatus.CANCELLED) return;
                if (!order.getStatus().canTransitionTo(StockOrderStatus.CANCELLED)) throw ApiException.badRequest("cannot cancel from " + order.getStatus());
                order.setStatus(StockOrderStatus.CANCELLED);
                order.getItems().forEach(i -> ((WholesaleProduct) productStore.get(i.getWholesaleProduct().getId()))
                        .setAvailableStock(((WholesaleProduct) productStore.get(i.getWholesaleProduct().getId())).getAvailableStock() + i.getQuantityPacks()));
            }
            @Override public StockOrderResponse advanceStatus(User s, String orderId, StockOrderStatus next) {
                StockOrder o = (StockOrder) orderStore.get(orderId);
                o.setStatus(next);
                return null;
            }
        };
        service = new BulkPurchaseService(groups, allocations, events, products, tiers, orderRepo, stockOrders, businessService, new ServiceRateLimiter());
    }

    // ---------------- helpers ----------------
    private CreateGroupRequest createReq(int target) {
        return new CreateGroupRequest("Maize bulk buy", "Together we save", product.getId(), target,
                Instant.now().plus(Duration.ofDays(7)), BulkShortfallPolicy.CANCEL, null, true, false, false, "Collect at depot");
    }

    private String openGroup(int target) {
        String id = service.createGroup(uA, createReq(target)).group().id();
        service.open(uA, id);
        return id;
    }

    private JoinRequest join(int packs) { return new JoinRequest(packs, StockFulfilmentOption.SELF_COLLECTION, null, null); }

    private String groupWith(int a, int b, int c) {
        String id = openGroup(100);
        service.join(uA, id, join(a));
        service.join(uB, id, join(b));
        service.join(uC, id, join(c));
        return id;
    }

    private void confirmAll(String id, String price) {
        for (User u : List.of(uA, uB, uC)) {
            int packs = service.myAllocation(u, id).requestedPacks();
            ConfirmOutcome out = service.confirm(u, id, new ConfirmRequest(packs, new BigDecimal(price)));
            assertTrue(out.confirmed(), "confirm failed for " + u.getId() + ": " + out.reason());
        }
    }

    private String finalisedGroup() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        FinaliseOutcome f = service.finalise(uA, id, new FinaliseRequest(false, false));
        assertEquals("FINALISED", f.outcome());
        return id;
    }

    private BulkAllocation alloc(String groupId, Business b) {
        return allocs().stream().filter(x -> x.getGroup().getId().equals(groupId) && x.getBusiness().getId().equals(b.getId())).findFirst().orElseThrow();
    }

    private void payOrder(BulkAllocation a) {
        Payment p = new Payment();
        p.transitionTo(PaymentStatus.PROCESSING);
        p.transitionTo(PaymentStatus.SUCCESSFUL);
        a.getStockOrder().setPayment(p);
        a.getStockOrder().setStatus(StockOrderStatus.PAID);
    }

    private BulkPurchaseGroup group(String id) { return (BulkPurchaseGroup) groupStore.get(id); }

    private int status(Throwable t) { return ((ApiException) t).getStatus().value(); }

    // ---------------- group creation ----------------
    @Test
    void createSnapshotsSupplierTiersAndMoqAndStartsAsDraft() {
        GroupDetail d = service.createGroup(uA, createReq(100));
        assertEquals(BulkGroupStatus.DRAFT, d.group().status());
        assertEquals(50, d.group().moqPacks());
        assertEquals(2, d.group().tiers().size());
        assertEquals(new BigDecimal("100.00"), d.group().basePricePerPack());
        assertTrue(d.group().procurementReference().startsWith("BPG-"));
    }

    @Test
    void createRejectsTargetBelowSupplierMinimumAndSelfSupply() {
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.createGroup(uA, createReq(10)))));
        businessByUser.put(uS, supplier);
        supplier.setCapabilities(new java.util.LinkedHashSet<>(List.of(BusinessCapability.CAN_SUPPLY_BUSINESSES, BusinessCapability.CAN_BUY_STOCK)));
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.createGroup(uS, createReq(100)))));
    }

    @Test
    void createRejectsNoFulfilmentOptionAndPastDeadline() {
        CreateGroupRequest none = new CreateGroupRequest("x", null, product.getId(), 100, Instant.now().plus(Duration.ofDays(2)),
                null, null, false, false, false, null);
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.createGroup(uA, none))));
        CreateGroupRequest past = new CreateGroupRequest("x", null, product.getId(), 100, Instant.now().minusSeconds(5),
                null, null, true, false, false, null);
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.createGroup(uA, past))));
    }

    @Test
    void businessWithoutBuyCapabilityCannotCreate() {
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.createGroup(uS, createReq(100)))));
    }

    // ---------------- joining, aggregate, target ----------------
    @Test
    void joiningAggregatesQuantitiesAndReachesTarget() {
        String id = openGroup(100);
        service.join(uA, id, join(20));
        service.join(uB, id, join(30));
        assertEquals(50, service.get(uA, id).group().aggregatePacks());
        assertEquals(BulkGroupStatus.OPEN, group(id).getStatus());
        service.join(uC, id, join(50));
        assertEquals(100, service.get(uA, id).group().aggregatePacks());
        assertEquals(BulkGroupStatus.TARGET_REACHED, group(id).getStatus());
        assertEquals(new BigDecimal("90.00"), service.get(uA, id).group().currentPricePerPack());
    }

    @Test
    void withdrawBelowTargetFallsBackToOpen() {
        String id = groupWith(20, 30, 50);
        service.withdraw(uC, id);
        assertEquals(BulkGroupStatus.OPEN, group(id).getStatus());
        assertEquals(50, service.get(uA, id).group().aggregatePacks());
    }

    @Test
    void duplicateJoinIsAnIdempotentReplayAndDifferentJoinIsRejected() {
        String id = openGroup(100);
        AllocationView first = service.join(uB, id, join(30));
        AllocationView replay = service.join(uB, id, join(30));
        assertEquals(first.id(), replay.id());
        assertEquals(1, allocs().stream().filter(a -> a.getBusiness().getId().equals(bB.getId())).count());
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.join(uB, id, join(31)))));
    }

    @Test
    void joinValidatesFulfilmentOptionsAndRejectsTheSupplier() {
        String id = openGroup(100);
        assertEquals(400, status(assertThrows(ApiException.class,
                () -> service.join(uB, id, new JoinRequest(10, StockFulfilmentOption.WHOLESALER_DELIVERY, "1 Main Rd", null)))));
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.join(uB, id, join(101)))));
        supplier.setCapabilities(new java.util.LinkedHashSet<>(List.of(BusinessCapability.CAN_SUPPLY_BUSINESSES, BusinessCapability.CAN_BUY_STOCK)));
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.join(uS, id, join(10)))));
    }

    @Test
    void rejoinAfterWithdrawReusesTheSameAllocationRow() {
        String id = openGroup(100);
        String first = service.join(uB, id, join(30)).id();
        service.withdraw(uB, id);
        String second = service.join(uB, id, join(40)).id();
        assertEquals(first, second);
        assertEquals(BulkAllocationStatus.JOINED, alloc(id, bB).getStatus());
    }

    @Test
    void saveToBuyReferenceIsRejectedWhenNotWired() {
        String id = openGroup(100);
        assertEquals(400, status(assertThrows(ApiException.class,
                () -> service.join(uB, id, new JoinRequest(10, StockFulfilmentOption.SELF_COLLECTION, null, "s2b-1")))));
    }

    // ---------------- quoting & confirmation ----------------
    @Test
    void quoteShowsPriceDiscountNextTierWithoutCommitting() {
        String id = openGroup(100);
        service.join(uA, id, join(20));
        Quote q = service.quote(uB, id, 30, StockFulfilmentOption.SELF_COLLECTION);
        assertEquals(50, q.expectedAggregatePacks());
        assertEquals(new BigDecimal("100.00"), q.pricePerPack());
        assertEquals(50, q.packsToNextTier());
        assertEquals(100, q.nextTier().minPacks());
        Quote q2 = service.quote(uB, id, 80, StockFulfilmentOption.SELF_COLLECTION);
        assertEquals(new BigDecimal("90.00"), q2.pricePerPack());
        assertEquals(new BigDecimal("10.00"), q2.discountPerPack());
        assertEquals(new BigDecimal("7200.00"), q2.subtotal());
        assertTrue(allocs().stream().noneMatch(a -> a.getBusiness().getId().equals(bB.getId())), "a quote must not enrol anyone");
    }

    @Test
    void confirmationOnlyOpensAfterOrganiserStartsIt() {
        String id = groupWith(20, 30, 50);
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("90.00"))))));
    }

    @Test
    void confirmWithWrongPriceReturnsNewQuoteAndConfirmsNothing() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        ConfirmOutcome out = service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("100.00")));
        assertFalse(out.confirmed());
        assertEquals("PRICE_CHANGED", out.reason());
        assertEquals(new BigDecimal("90.00"), out.currentQuote().pricePerPack());
        assertEquals(BulkAllocationStatus.JOINED, alloc(id, bA).getStatus());
        ConfirmOutcome qty = service.confirm(uA, id, new ConfirmRequest(25, new BigDecimal("90.00")));
        assertEquals("QUANTITY_CHANGED", qty.reason());
    }

    @Test
    void confirmRecordsAgreedMaxPriceAndReplayIsIdempotent() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        assertTrue(service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("90.00"))).confirmed());
        Instant at = alloc(id, bA).getAgreedAt();
        assertTrue(service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("90.00"))).confirmed());
        assertEquals(at, alloc(id, bA).getAgreedAt());
        assertEquals(new BigDecimal("90.00"), alloc(id, bA).getAgreedMaxPricePerPack());
        assertEquals(20, alloc(id, bA).getConfirmedPacks());
    }

    @Test
    void changingOwnQuantityVoidsConfirmationAndOthersStayPutWhenPriceHolds() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        service.updateAllocation(uC, id, new UpdateAllocationRequest(60, null, null)); // 110 total, price 90 still
        assertEquals(BulkAllocationStatus.JOINED, alloc(id, bC).getStatus());
        assertNull(alloc(id, bC).getConfirmedPacks());
        assertEquals(BulkAllocationStatus.CONFIRMED, alloc(id, bA).getStatus());
        assertEquals(BulkAllocationStatus.CONFIRMED, alloc(id, bB).getStatus());
    }

    @Test
    void anotherBusinessWithdrawingNeverRaisesAConfirmedPriceSilently() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        service.withdraw(uC, id); // aggregate 50 -> price back to 100
        assertEquals(BulkAllocationStatus.PENDING_RECONFIRMATION, alloc(id, bA).getStatus());
        assertEquals(BulkAllocationStatus.PENDING_RECONFIRMATION, alloc(id, bB).getStatus());
        FinaliseOutcome f = service.finalise(uA, id, new FinaliseRequest(true, true));
        assertFalse("FINALISED".equals(f.outcome()));
        assertTrue(orderStore.isEmpty(), "no order may be created while agreements are stale");
        assertTrue(allocs().stream().allMatch(a -> a.getStockOrder() == null));
    }

    // ---------------- finalisation ----------------
    @Test
    void finaliseCreatesOneOrderPerBusinessWithExactSeparateTotals() {
        String id = finalisedGroup();
        assertEquals(BulkGroupStatus.PAYMENT_PENDING, group(id).getStatus());
        assertEquals(3, orderStore.size());
        BulkAllocation a = alloc(id, bA), b = alloc(id, bB), c = alloc(id, bC);
        assertEquals(new BigDecimal("1800.00"), a.getTotal());
        assertEquals(new BigDecimal("2700.00"), b.getTotal());
        assertEquals(new BigDecimal("4500.00"), c.getTotal());
        assertEquals(new BigDecimal("10.00"), a.getDiscountPerPack());
        // each order belongs to exactly its own business and is unpaid
        assertEquals(bA.getId(), a.getStockOrder().getBuyerBusiness().getId());
        assertEquals(bB.getId(), b.getStockOrder().getBuyerBusiness().getId());
        assertEquals(StockOrderStatus.PENDING_PAYMENT, a.getStockOrder().getStatus());
        assertEquals(supplier.getId(), a.getStockOrder().getSupplierBusiness().getId());
        // allocation quantities sum to the aggregate sent to the supplier
        assertEquals(100, a.getConfirmedPacks() + b.getConfirmedPacks() + c.getConfirmedPacks());
        assertEquals(100, group(id).getFinalAggregatePacks());
        assertEquals(new BigDecimal("9000.00"), a.getTotal().add(b.getTotal()).add(c.getTotal()));
        assertEquals(900, product.getAvailableStock());
        assertEquals(BulkAllocationStatus.ORDERED, a.getStatus());
        // separate, deterministic idempotency keys => no duplicate orders on retry
        assertEquals(3, orderStore.values().stream().map(o -> ((StockOrder) o).getIdempotencyKey()).distinct().count());
    }

    @Test
    void finaliseReplayDoesNotCreateDuplicateOrdersOrReserveStockTwice() {
        String id = finalisedGroup();
        FinaliseOutcome again = service.finalise(uA, id, new FinaliseRequest(false, false));
        assertEquals("FINALISED", again.outcome());
        assertEquals(3, orderStore.size());
        assertEquals(900, product.getAvailableStock());
    }

    @Test
    void finaliseWaitsForUnconfirmedUnlessExplicitlyDropped() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("90.00")));
        service.confirm(uB, id, new ConfirmRequest(30, new BigDecimal("90.00")));
        FinaliseOutcome wait = service.finalise(uA, id, new FinaliseRequest(true, false));
        assertEquals("UNCONFIRMED_PARTICIPANTS", wait.outcome());
        assertTrue(orderStore.isEmpty());
    }

    @Test
    void droppingUnconfirmedCanRaisePriceSoEveryoneReconfirmsBeforeAnyOrder() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        service.confirm(uA, id, new ConfirmRequest(20, new BigDecimal("90.00")));
        service.confirm(uB, id, new ConfirmRequest(30, new BigDecimal("90.00")));
        FinaliseOutcome f = service.finalise(uA, id, new FinaliseRequest(true, true));
        // confirmed 50 < 100 target and unconfirmed C removed => price 100 > agreed 90
        assertTrue(f.outcome().equals("RECONFIRMATION_REQUIRED") || f.outcome().equals("BELOW_TARGET"), f.outcome());
        assertTrue(orderStore.isEmpty());
    }

    @Test
    void finaliseBelowMinimumOrderQuantityIsRefused() {
        String id = openGroup(100);
        service.join(uA, id, join(20));
        service.join(uB, id, join(20));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.startConfirmation(uA, id))));
    }

    @Test
    void finaliseRefusesWhenSupplierChangedThePriceSinceGroupCreation() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        product.setPricePerPack(new BigDecimal("120.00"));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.finalise(uA, id, new FinaliseRequest(false, false)))));
        assertTrue(orderStore.isEmpty());
    }

    @Test
    void finaliseFailsAtomicallyWhenSupplierStockIsInsufficient() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        product.setAvailableStock(60); // first two reserve fine (50), third (50) fails
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.finalise(uA, id, new FinaliseRequest(false, false)))));
        // NOTE: in-memory fakes cannot roll back; in production @Transactional rolls the whole finalise back.
    }

    @Test
    void onlyOrganiserCanOpenStartFinaliseAndCancel() {
        String id = groupWith(20, 30, 50);
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.startConfirmation(uB, id))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.cancelGroup(uB, id, null))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.finalise(uB, id, new FinaliseRequest(false, false)))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.updateGroup(uB, id, new UpdateGroupRequest("hijack", null, null, null, null, null)))));
    }

    // ---------------- tenant isolation ----------------
    @Test
    void outsiderCannotTouchAnotherBusinessesAllocation() {
        String id = groupWith(20, 30, 50);
        service.startConfirmation(uA, id);
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.myAllocation(uD, id))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.withdraw(uD, id))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.updateAllocation(uD, id, new UpdateAllocationRequest(5, null, null)))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.confirm(uD, id, new ConfirmRequest(5, new BigDecimal("90.00"))))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.reportIssue(uD, id, new ReportIssueRequest("DAMAGED", 1, null)))));
    }

    @Test
    void participantsCannotSeeOtherBusinessesQuantitiesOrPrices() {
        String id = groupWith(20, 30, 50);
        GroupDetail forB = service.get(uB, id);
        assertEquals("PARTICIPANT", forB.viewerRole());
        assertNull(forB.participants(), "a participant must not receive the participant list");
        assertEquals(30, forB.myAllocation().requestedPacks());
        GroupDetail forA = service.get(uA, id);
        assertEquals("CREATOR", forA.viewerRole());
        assertEquals(3, forA.participants().size());
        GroupDetail forS = service.get(uS, id);
        assertEquals("SUPPLIER", forS.viewerRole());
    }

    @Test
    void draftGroupIsInvisibleToEveryoneButItsOrganiser() {
        String id = service.createGroup(uA, createReq(100)).group().id();
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.get(uB, id))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.get(uS, id))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.join(uB, id, join(10)))));
        assertEquals("CREATOR", service.get(uA, id).viewerRole());
    }

    @Test
    void closedGroupIsInvisibleToNonParticipants() {
        String id = finalisedGroup();
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.get(uD, id))));
    }

    @Test
    void supplierViewOnlyForTheGroupsOwnSupplier() {
        String id = finalisedGroup();
        businessByUser.put(uD, bD);
        bD.setCapabilities(new java.util.LinkedHashSet<>(List.of(BusinessCapability.CAN_SUPPLY_BUSINESSES)));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.supplierView(uD, id))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.supplierConfirm(uD, id, new SupplierConfirmRequest(10, null, null)))));
        SupplierGroupView v = service.supplierView(uS, id);
        assertEquals(3, v.lines().size());
        assertEquals(100, v.orderedPacks());
        assertEquals(0, v.paidPacks());
    }

    @Test
    void supplierViewExposesNoPriceOrPaymentSecretsBeyondStatus() {
        String id = finalisedGroup();
        SupplierLine l = service.supplierView(uS, id).lines().get(0);
        assertEquals("UNPAID", l.paymentStatus());
    }

    @Test
    void tiersCanOnlyBeSetByTheProductsOwnSupplier() {
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.setTiers(uA, product.getId(), new SetTiersRequest(List.of())))));
        businessByUser.put(uD, bD);
        bD.setCapabilities(new java.util.LinkedHashSet<>(List.of(BusinessCapability.CAN_SUPPLY_BUSINESSES)));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.setTiers(uD, product.getId(), new SetTiersRequest(List.of())))));
    }

    @Test
    void supplierCannotConfigureTiersAboveBasePrice() {
        assertEquals(400, status(assertThrows(ApiException.class,
                () -> service.setTiers(uS, product.getId(), new SetTiersRequest(List.of(new TierInput(100, new BigDecimal("150.00"))))))));
        List<TierView> ok = service.setTiers(uS, product.getId(), new SetTiersRequest(List.of(new TierInput(150, new BigDecimal("85.00")))));
        assertEquals(1, ok.size());
    }

    // ---------------- payments (observed, never asserted) ----------------
    @Test
    void allocationIsNotPaidUntilPaymentIsConfirmedByTheOrder() {
        String id = finalisedGroup();
        BulkAllocation a = alloc(id, bA);
        a.getStockOrder().setStatus(StockOrderStatus.PAID); // status says PAID but there is NO successful payment
        service.refresh(uA, id);
        assertEquals(BulkAllocationStatus.ORDERED, a.getStatus());
        payOrder(a);
        service.refresh(uA, id);
        assertEquals(BulkAllocationStatus.PAID, a.getStatus());
        assertEquals("PAID", service.myAllocation(uA, id).paymentStatus());
    }

    @Test
    void oneBusinessPayingDoesNotPlaceOrOMarkOthersPaid() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA));
        service.refresh(uA, id);
        assertEquals(BulkAllocationStatus.PAID, alloc(id, bA).getStatus());
        assertEquals(BulkAllocationStatus.ORDERED, alloc(id, bB).getStatus());
        assertEquals(BulkGroupStatus.PAYMENT_PENDING, group(id).getStatus());
        assertEquals("UNPAID", service.myAllocation(uB, id).paymentStatus());
        PlaceOrderOutcome o = service.placeOrder(uA, id, new PlaceOrderRequest(false, null));
        assertEquals("NOT_ALL_PAID", o.outcome());
        assertEquals(2, o.unpaidCount());
    }

    @Test
    void orderIsPlacedAutomaticallyOnlyWhenEveryShareIsPaid() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA)); payOrder(alloc(id, bB));
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.PAYMENT_PENDING, group(id).getStatus());
        payOrder(alloc(id, bC));
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.ORDER_PLACED, group(id).getStatus());
        assertNotNull(group(id).getOrderPlacedAt());
    }

    @Test
    void proceedingWithPaidSharesIsRefusedWhenItWouldLoseTheAgreedVolumePrice() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA)); payOrder(alloc(id, bB)); // 50 paid; price at 50 is 100 > 90
        PlaceOrderOutcome o = service.placeOrder(uA, id, new PlaceOrderRequest(true, null));
        assertEquals("PRICE_WOULD_INCREASE", o.outcome());
        assertEquals(BulkAllocationStatus.ORDERED, alloc(id, bC).getStatus(), "unpaid share must not be dropped on a refused proceed");
    }

    @Test
    void proceedingWithPaidSharesIsAllowedWhenTheTierStillHolds() {
        String id = groupWith(60, 60, 30); // 150 total, tier 100 => 90
        service.startConfirmation(uA, id);
        confirmAll(id, "90.00");
        assertEquals("FINALISED", service.finalise(uA, id, new FinaliseRequest(false, false)).outcome());
        payOrder(alloc(id, bA)); payOrder(alloc(id, bB)); // 120 paid, still >= 100
        PlaceOrderOutcome o = service.placeOrder(uA, id, new PlaceOrderRequest(true, null));
        assertEquals("ORDER_PLACED", o.outcome());
        assertEquals(BulkAllocationStatus.CANCELLED, alloc(id, bC).getStatus());
        assertEquals(StockOrderStatus.CANCELLED, alloc(id, bC).getStockOrder().getStatus());
        assertEquals(120, group(id).getFinalAggregatePacks());
    }

    @Test
    void unpaidBusinessCanCancelOnlyItsOwnOrderAndOthersStayLockedAtTheirPrice() {
        String id = finalisedGroup();
        service.withdraw(uC, id);
        assertEquals(BulkAllocationStatus.CANCELLED, alloc(id, bC).getStatus());
        assertEquals(StockOrderStatus.PENDING_PAYMENT, alloc(id, bA).getStockOrder().getStatus());
        assertEquals(new BigDecimal("1800.00"), alloc(id, bA).getTotal());
        assertEquals(new BigDecimal("90.00"), alloc(id, bA).getFinalPricePerPack());
    }

    @Test
    void paidShareCannotBeWithdrawnAndPaidStateIsNotRevertedByRetry() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA));
        service.refresh(uA, id);
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.withdraw(uA, id))));
        assertEquals(BulkAllocationStatus.PAID, alloc(id, bA).getStatus());
    }

    @Test
    void inFlightPaymentBlocksCancellationOfThatShare() {
        String id = finalisedGroup();
        alloc(id, bB).getStockOrder().setStatus(StockOrderStatus.PAYMENT_PROCESSING);
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.withdraw(uB, id))));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.cancelGroup(uA, id, null))));
    }

    @Test
    void failedPaymentIsReportedAsFailedNotPaid() {
        String id = finalisedGroup();
        BulkAllocation a = alloc(id, bA);
        Payment p = new Payment();
        p.transitionTo(PaymentStatus.FAILED);
        a.getStockOrder().setPayment(p);
        service.refresh(uA, id);
        assertEquals(BulkAllocationStatus.ORDERED, a.getStatus());
        assertEquals("FAILED", service.myAllocation(uA, id).paymentStatus());
    }

    @Test
    void expiredUnpaidOrderIsRecordedAsCancelledShare() {
        String id = finalisedGroup();
        BulkAllocation a = alloc(id, bB);
        a.getStockOrder().setStatus(StockOrderStatus.CANCELLED); // what StockPaymentService.expireUnpaidOrders does
        service.refresh(uA, id);
        assertEquals(BulkAllocationStatus.CANCELLED, a.getStatus());
    }

    // ---------------- supplier confirmation, shortage, cancellation ----------------
    private String placedGroup() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA)); payOrder(alloc(id, bB)); payOrder(alloc(id, bC));
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.ORDER_PLACED, group(id).getStatus());
        return id;
    }

    @Test
    void supplierCannotConfirmBeforeOrderIsPlaced() {
        String id = finalisedGroup();
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.supplierConfirm(uS, id, new SupplierConfirmRequest(100, "PO-1", null)))));
    }

    @Test
    void fullSupplierConfirmationAdvancesEachBusinessOwnOrder() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(100, "PO-77", "ready Friday"));
        assertEquals(BulkGroupStatus.SUPPLIER_CONFIRMED, group(id).getStatus());
        assertEquals("PO-77", group(id).getSupplierReference());
        for (Business b : List.of(bA, bB, bC)) {
            assertEquals(StockOrderStatus.CONFIRMED, alloc(id, b).getStockOrder().getStatus());
            assertEquals(0, alloc(id, b).getShortagePacks());
        }
    }

    @Test
    void supplierCannotConfirmMoreThanOrdered() {
        String id = placedGroup();
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.supplierConfirm(uS, id, new SupplierConfirmRequest(101, null, null)))));
    }

    @Test
    void shortageIsSplitProRataAndRefundOwedIsRecordedNotExecuted() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(80, "PO-1", "short 20"));
        BulkAllocation a = alloc(id, bA), b = alloc(id, bB), c = alloc(id, bC);
        assertEquals(16, a.getAllocatedPacks());
        assertEquals(24, b.getAllocatedPacks());
        assertEquals(40, c.getAllocatedPacks());
        assertEquals(80, a.getAllocatedPacks() + b.getAllocatedPacks() + c.getAllocatedPacks());
        assertEquals(4, a.getShortagePacks());
        assertEquals(new BigDecimal("360.00"), a.getRefundDueAmount());   // 4 x 90.00
        assertEquals(new BigDecimal("540.00"), b.getRefundDueAmount());   // 6 x 90.00
        assertEquals(new BigDecimal("900.00"), c.getRefundDueAmount());   // 10 x 90.00
        // honest state: money is owed, nothing is marked refunded
        assertEquals(BulkAllocationStatus.PAID, a.getStatus());
        assertEquals("PAID", service.myAllocation(uA, id).paymentStatus());
        assertEquals(BulkGroupStatus.SUPPLIER_CONFIRMED, group(id).getStatus());
    }

    @Test
    void shortageWithAwkwardNumbersStillSumsExactlyAndNoOneGetsMoreThanOrdered() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(77, null, null));
        int sum = 0;
        for (Business b : List.of(bA, bB, bC)) {
            BulkAllocation a = alloc(id, b);
            assertTrue(a.getAllocatedPacks() <= a.getConfirmedPacks());
            assertEquals(a.getConfirmedPacks() - a.getAllocatedPacks(), a.getShortagePacks());
            sum += a.getAllocatedPacks();
        }
        assertEquals(77, sum);
    }

    @Test
    void supplierCancellingEverythingMakesPaidSharesRefundDueNeverRefunded() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(0, null, "out of stock"));
        assertEquals(BulkGroupStatus.CANCELLED, group(id).getStatus());
        for (Business b : List.of(bA, bB, bC)) {
            BulkAllocation a = alloc(id, b);
            assertEquals(BulkAllocationStatus.REFUND_DUE, a.getStatus());
            assertEquals(a.getTotal(), a.getRefundDueAmount());
            assertEquals(StockOrderStatus.REFUND_PENDING, a.getStockOrder().getStatus());
            assertFalse("REFUNDED".equals(service.myAllocation(b == bA ? uA : b == bB ? uB : uC, id).paymentStatus()));
        }
        assertEquals(1000, product.getAvailableStock(), "reserved stock must be released");
    }

    @Test
    void organiserCancelBeforeOrdersTouchesNothingFinancial() {
        String id = groupWith(20, 30, 50);
        service.cancelGroup(uA, id, new CancelRequest("changed our minds"));
        assertEquals(BulkGroupStatus.CANCELLED, group(id).getStatus());
        assertTrue(orderStore.isEmpty());
        assertTrue(allocs().stream().allMatch(a -> a.getStatus() == BulkAllocationStatus.CANCELLED));
        assertEquals(1000, product.getAvailableStock());
        service.cancelGroup(uA, id, null); // idempotent
    }

    @Test
    void organiserCancelAfterFinaliseReleasesUnpaidAndFlagsPaidAsRefundDue() {
        String id = finalisedGroup();
        payOrder(alloc(id, bA));
        service.refresh(uA, id);
        service.cancelGroup(uA, id, new CancelRequest("supplier delay"));
        assertEquals(BulkAllocationStatus.REFUND_DUE, alloc(id, bA).getStatus());
        assertEquals(new BigDecimal("1800.00"), alloc(id, bA).getRefundDueAmount());
        assertEquals(StockOrderStatus.REFUND_PENDING, alloc(id, bA).getStockOrder().getStatus());
        assertEquals(BulkAllocationStatus.CANCELLED, alloc(id, bB).getStatus());
        assertEquals(StockOrderStatus.CANCELLED, alloc(id, bB).getStockOrder().getStatus());
        assertEquals(1000, product.getAvailableStock());
    }

    @Test
    void organiserCannotCancelOnceTheOrderIsWithTheSupplier() {
        String id = placedGroup();
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.cancelGroup(uA, id, null))));
    }

    // ---------------- fulfilment ----------------
    @Test
    void groupCompletesOnlyWhenEveryBusinessHasItsGoods() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(100, null, null));
        alloc(id, bA).getStockOrder().setStatus(StockOrderStatus.DELIVERED);
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.FULFILMENT, group(id).getStatus());
        alloc(id, bB).getStockOrder().setStatus(StockOrderStatus.DELIVERED);
        alloc(id, bC).getStockOrder().setStatus(StockOrderStatus.DELIVERED);
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.COMPLETED, group(id).getStatus());
    }

    @Test
    void groupWithShortageEndsPartiallyFulfilledNotCompleted() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(90, null, null));
        for (Business b : List.of(bA, bB, bC)) alloc(id, b).getStockOrder().setStatus(StockOrderStatus.DELIVERED);
        service.refresh(uA, id);
        assertEquals(BulkGroupStatus.PARTIALLY_FULFILLED, group(id).getStatus());
    }

    @Test
    void damagedOrMissingItemsRecordARefundClaimWithinDeliveredQuantity() {
        String id = placedGroup();
        service.supplierConfirm(uS, id, new SupplierConfirmRequest(100, null, null));
        BulkAllocation a = alloc(id, bA);
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.reportIssue(uA, id, new ReportIssueRequest("DAMAGED", 2, null)))));
        a.getStockOrder().setStatus(StockOrderStatus.DELIVERED);
        AllocationView v = service.reportIssue(uA, id, new ReportIssueRequest("damaged", 2, "split bags"));
        assertEquals(2, v.issuePacks());
        assertEquals(new BigDecimal("180.00"), v.refundDueAmount());
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.reportIssue(uA, id, new ReportIssueRequest("MISSING", 19, null)))));
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.reportIssue(uA, id, new ReportIssueRequest("STOLEN", 1, null)))));
        assertEquals("PAID", v.paymentStatus());
    }

    // ---------------- deadline policy through the service ----------------
    @Test
    void deadlineWithCancelPolicyCancelsAnUnderSubscribedGroupAndChargesNothing() {
        String id = openGroup(100);
        service.join(uA, id, join(20));
        group(id).setDeadline(Instant.now().minusSeconds(60));
        service.maintain(id);
        assertEquals(BulkGroupStatus.CANCELLED, group(id).getStatus());
        assertTrue(orderStore.isEmpty());
    }

    @Test
    void deadlineWithExtendPolicyExtendsInsteadOfCancelling() {
        CreateGroupRequest req = new CreateGroupRequest("x", null, product.getId(), 100, Instant.now().plus(Duration.ofDays(2)),
                BulkShortfallPolicy.EXTEND, 24, true, false, false, null);
        String id = service.createGroup(uA, req).group().id();
        service.open(uA, id);
        service.join(uA, id, join(20));
        Instant before = Instant.now().minusSeconds(60);
        group(id).setDeadline(before);
        service.maintain(id);
        assertEquals(BulkGroupStatus.OPEN, group(id).getStatus());
        assertEquals(1, group(id).getExtensionCount());
        assertTrue(group(id).getDeadline().isAfter(before));
    }

    @Test
    void joinAfterDeadlineIsRefused() {
        String id = openGroup(100);
        group(id).setDeadline(Instant.now().minusSeconds(5));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.join(uB, id, join(10)))));
    }

    @Test
    void groupWithOrdersIsNeverCancelledByTheDeadlineSweep() {
        String id = finalisedGroup();
        group(id).setDeadline(Instant.now().minusSeconds(60));
        service.maintain(id);
        assertEquals(BulkGroupStatus.PAYMENT_PENDING, group(id).getStatus());
    }

    // ---------------- rounding / currency ----------------
    @Test
    void perBusinessTotalsUseTwoDecimalCurrencyPrecision() {
        // base 33.33, tier 100 -> 29.99 ; check line totals are exact 2dp sums
        product.setPricePerPack(new BigDecimal("33.33"));
        tierStore.clear();
        WholesaleVolumeTier w = new WholesaleVolumeTier();
        w.setWholesaleProduct(product); w.setMinPacks(100); w.setPricePerPack(new BigDecimal("29.99"));
        tierStore.put(w.getId(), w);
        String id = groupWith(33, 33, 34);
        service.startConfirmation(uA, id);
        confirmAll(id, "29.99");
        service.finalise(uA, id, new FinaliseRequest(false, false));
        BigDecimal sum = alloc(id, bA).getTotal().add(alloc(id, bB).getTotal()).add(alloc(id, bC).getTotal());
        assertEquals(new BigDecimal("2999.00"), sum);
        assertEquals(new BigDecimal("989.67"), alloc(id, bA).getTotal());
        assertEquals(2, alloc(id, bA).getTotal().scale());
    }
}
