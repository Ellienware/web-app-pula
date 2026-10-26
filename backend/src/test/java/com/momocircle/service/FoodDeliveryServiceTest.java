package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.DeliveryEarningsAllocation;
import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryEarningsAllocationRepository;
import com.momocircle.repository.DeliveryRequestRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers PULA Food delivery: automatic
 * dispatch rules, idempotency, driver withdrawal, unclaimed-delivery timers, and the 80/20 allocation.
 */
class FoodDeliveryServiceTest {

    private DeliveryRequestRepository deliveries;
    private DeliveryEarningsAllocationRepository allocations;
    private NotificationService notifications;
    private FoodProperties props;
    private FoodDeliveryService service;

    private User buyer;
    private User seller;
    private Business business;
    private Order order;

    @BeforeEach
    void setUp() {
        deliveries = mock(DeliveryRequestRepository.class);
        allocations = mock(DeliveryEarningsAllocationRepository.class);
        notifications = mock(NotificationService.class);
        props = new FoodProperties();
        service = new FoodDeliveryService(deliveries, allocations, notifications, props);

        buyer = new User();
        buyer.setId("buyer-1");
        seller = new User();
        seller.setId("seller-1");
        business = new Business();
        business.setId("biz-1");
        business.setOwner(seller);
        business.setLocation("12 Vilakazi St, Soweto");

        order = new Order();
        order.setId("o1");
        order.setBuyer(buyer);
        order.setBusiness(business);
        order.setKind(OrderKind.FOOD);
        order.setRestaurantDecision(RestaurantDecision.ACCEPTED);
        order.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        order.setDeliveryAddress("5 Main Rd, Diepkloof");
        order.setDeliveryFeeSnapshot(new BigDecimal("25.00"));

        when(deliveries.save(any(DeliveryRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(allocations.save(any(DeliveryEarningsAllocation.class))).thenAnswer(i -> i.getArgument(0));
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.empty());
    }

    private DeliveryRequest delivery(DeliveryStatus status) {
        DeliveryRequest d = new DeliveryRequest();
        d.setId("d1");
        d.setOrder(order);
        d.setStatus(status);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        d.setDriverSearchStartedAt(Instant.now());
        return d;
    }

    /** A REQUESTED delivery whose current driver search began {@code minutesAgo} minutes ago. */
    private DeliveryRequest searching(long minutesAgo) {
        DeliveryRequest d = delivery(DeliveryStatus.REQUESTED);
        d.setDriverSearchStartedAt(Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        return d;
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void readyDeliveryOrderCreatesADeliveryFromTheFeeSnapshot() {
        DeliveryRequest d = service.dispatchIfEligible(order).orElseThrow();
        assertEquals(DeliveryStatus.REQUESTED, d.getStatus());
        assertEquals("12 Vilakazi St, Soweto", d.getPickupLocation());
        assertEquals("5 Main Rd, Diepkloof", d.getDropoffLocation());
        assertEquals(new BigDecimal("25.00"), d.getEstimatedFee());
        assertSame(order, d.getOrder());
        verify(notifications).notify(eq(buyer), eq(NotificationType.DELIVERY_UPDATE), any(), eq("o1"));
    }

    @Test
    void initialDispatchStartsTheDriverSearchTimer() {
        Instant before = Instant.now();
        DeliveryRequest d = service.dispatchIfEligible(order).orElseThrow();
        Instant after = Instant.now();
        assertTrue(d.getDriverSearchStartedAt() != null);
        assertFalse(d.getDriverSearchStartedAt().isBefore(before));
        assertFalse(d.getDriverSearchStartedAt().isAfter(after));
        assertNull(d.getNoDriverNotifiedAt());
    }

    @Test
    void branchAddressIsThePickupWhenThereIsOne() {
        BusinessBranch b = new BusinessBranch();
        b.setAddress("77 Branch Rd");
        order.setBranch(b);
        assertEquals("77 Branch Rd", service.dispatchIfEligible(order).orElseThrow().getPickupLocation());
    }

    @Test
    void collectionOrdersNeverDispatch() {
        order.setFulfilmentMethod(FulfilmentOption.CUSTOMER_PICKUP);
        assertTrue(service.dispatchIfEligible(order).isEmpty());
        verify(deliveries, never()).save(any());
    }

    @Test
    void restaurantsOwnRidersNeverDispatchToPulaRide() {
        order.setFulfilmentMethod(FulfilmentOption.SELLER_DELIVERY);
        assertTrue(service.dispatchIfEligible(order).isEmpty());
        verify(deliveries, never()).save(any());
    }

    @Test
    void retailOrdersAndUnacceptedFoodOrdersNeverDispatch() {
        order.setKind(OrderKind.RETAIL);
        assertTrue(service.dispatchIfEligible(order).isEmpty());
        order.setKind(OrderKind.FOOD);
        order.setRestaurantDecision(RestaurantDecision.PENDING);
        assertTrue(service.dispatchIfEligible(order).isEmpty());
        verify(deliveries, never()).save(any());
    }

    @Test
    void duplicateReadyEventReturnsTheExistingDeliveryWithoutCreatingAnother() {
        DeliveryRequest existing = delivery(DeliveryStatus.ACCEPTED);
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(existing));
        assertSame(existing, service.dispatchIfEligible(order).orElseThrow());
        verify(deliveries, never()).save(any());
    }

    @Test
    void missingPickupOrDropoffIsRefusedSoReadyCannotHalfSucceed() {
        business.setLocation(null);
        assertThrows(ApiException.class, () -> service.dispatchIfEligible(order));
        business.setLocation("12 Vilakazi St");
        order.setDeliveryAddress(" ");
        assertThrows(ApiException.class, () -> service.dispatchIfEligible(order));
    }

    // ------------------------------------------------------------------ driver withdrawal

    @Test
    void withdrawingDriverPutsTheSameDeliveryBackOutAndTellsEveryone() {
        DeliveryRequest d = delivery(DeliveryStatus.ACCEPTED);
        d.setDriver(new Driver());
        d.setAcceptedAt(Instant.now());

        DeliveryRequest out = service.reofferAfterDriverWithdrawal(d);

        assertEquals(DeliveryStatus.REQUESTED, out.getStatus());
        assertNull(out.getDriver());
        assertNull(out.getAcceptedAt());
        verify(notifications).notify(eq(buyer), eq(NotificationType.DELIVERY_UPDATE), any(), eq("o1"));
        verify(notifications).notify(eq(seller), eq(NotificationType.DELIVERY_UPDATE), any(), eq("o1"));
    }

    @Test
    void driverWithdrawalResetsTheSearchTimerAndRestartsTheCancellationWindow() {
        DeliveryRequest d = delivery(DeliveryStatus.ACCEPTED);
        d.setDriver(new Driver());
        d.setAcceptedAt(Instant.now());
        // The original search began long ago and the customer had already been told nobody came.
        Instant original = Instant.now().minus(Duration.ofMinutes(props.getNoDriverCancelMinutes() + 30));
        d.setDriverSearchStartedAt(original);
        d.setNoDriverNotifiedAt(Instant.now().minus(Duration.ofMinutes(15)));
        Instant before = Instant.now();

        DeliveryRequest out = service.reofferAfterDriverWithdrawal(d);

        assertEquals(DeliveryStatus.REQUESTED, out.getStatus());
        assertNull(out.getDriver());
        assertNull(out.getNoDriverNotifiedAt());
        assertFalse(out.getDriverSearchStartedAt().isBefore(before), "timer must restart at the withdrawal");
        assertTrue(out.getDriverSearchStartedAt().isAfter(original));
        verify(notifications).notify(eq(buyer), eq(NotificationType.DELIVERY_UPDATE), any(), eq("o1"));

        // The old search was past the cancellation timeout; the new one is not, so cancelling is refused again.
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(out));
        assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
        assertEquals(DeliveryStatus.REQUESTED, out.getStatus());
    }

    @Test
    void cannotGiveBackADeliveryThatIsAlreadyPickedUpOrNotAccepted() {
        assertThrows(ApiException.class, () -> service.reofferAfterDriverWithdrawal(delivery(DeliveryStatus.PICKED_UP)));
        assertThrows(ApiException.class, () -> service.reofferAfterDriverWithdrawal(delivery(DeliveryStatus.REQUESTED)));
    }

    // ------------------------------------------------------------------ nobody took it

    @Test
    void customerIsToldOnceAboutAnUnclaimedDelivery() {
        DeliveryRequest d = searching(props.getNoDriverNotifyMinutes() + 1);
        when(deliveries.findUnclaimedFoodDeliveriesCreatedBefore(any())).thenReturn(List.of(d));
        assertEquals(1, service.notifyUnclaimedDeliveries());
        assertTrue(d.getNoDriverNotifiedAt() != null);
        verify(notifications, times(1)).notify(eq(buyer), eq(NotificationType.DELIVERY_UPDATE), any(), eq("o1"));
    }

    @Test
    void theNoDriverNotificationDoesNotResetTheSearchTimer() {
        DeliveryRequest d = searching(props.getNoDriverNotifyMinutes() + 1);
        Instant started = d.getDriverSearchStartedAt();
        when(deliveries.findUnclaimedFoodDeliveriesCreatedBefore(any())).thenReturn(List.of(d));

        service.notifyUnclaimedDeliveries();

        assertEquals(started, d.getDriverSearchStartedAt());
        assertTrue(d.getNoDriverNotifiedAt() != null);
        // Even if persistence bumped updatedAt (it does, on every write), cancellation still counts from the search start.
        d.setUpdatedAt(Instant.now());
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
    }

    @Test
    void customerCannotCancelWhileWithinTheNoDriverWindow() {
        DeliveryRequest d = searching(props.getNoDriverCancelMinutes() - 5);
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
        assertEquals(DeliveryStatus.REQUESTED, d.getStatus());
        verify(deliveries, never()).saveAndFlush(any());
    }

    @Test
    void cancellationWindowIgnoresUpdatedAtSoAStaleUpdatedAtCannotOpenItEarly() {
        DeliveryRequest d = searching(1); // search started a minute ago
        d.setUpdatedAt(Instant.now().minus(Duration.ofHours(3))); // an old updatedAt must not count
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
        assertEquals(DeliveryStatus.REQUESTED, d.getStatus());
    }

    @Test
    void customerCanCancelAfterTheWindowAndTheDeliveryIsClosedSoNoDriverCanStillTakeIt() {
        DeliveryRequest d = searching(props.getNoDriverCancelMinutes() + 1);
        d.setUpdatedAt(Instant.now()); // a recent write (e.g. the notification) must not delay the cancel
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        service.cancelUnclaimedDeliveryForCustomer(order);
        assertEquals(DeliveryStatus.CANCELLED, d.getStatus());
        assertTrue(d.getCancelledAt() != null);
        verify(deliveries).saveAndFlush(d);
    }

    @Test
    void cancellationLosesTheRaceWhenADriverAcceptedFirst() {
        DeliveryRequest d = searching(props.getNoDriverCancelMinutes() + 1);
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        when(deliveries.saveAndFlush(d)).thenThrow(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(DeliveryRequest.class, "d1"));
        ApiException ex = assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void customerCannotCancelOnceADriverHasAccepted() {
        DeliveryRequest d = delivery(DeliveryStatus.ACCEPTED);
        d.setDriverSearchStartedAt(Instant.now().minus(Duration.ofHours(2)));
        when(deliveries.findByOrderId("o1")).thenReturn(Optional.of(d));
        assertThrows(ApiException.class, () -> service.cancelUnclaimedDeliveryForCustomer(order));
        assertEquals(DeliveryStatus.ACCEPTED, d.getStatus());
    }

    // ------------------------------------------------------------------ 80/20 allocation

    @Test
    void splitIsEightyTwentyAndAlwaysSumsToTheFee() {
        BigDecimal[] r25 = FoodDeliveryService.split(new BigDecimal("25.00"));
        assertEquals(new BigDecimal("20.00"), r25[0]);
        assertEquals(new BigDecimal("5.00"), r25[1]);

        // awkward cents: PULA takes the remainder so nothing is lost or invented
        for (String fee : new String[] {"33.33", "0.01", "17.55", "99.99", "20.05", "1.25"}) {
            BigDecimal f = new BigDecimal(fee);
            BigDecimal[] r = FoodDeliveryService.split(f);
            assertEquals(0, f.compareTo(r[0].add(r[1])), fee);
            assertTrue(r[0].signum() >= 0 && r[1].signum() >= 0, fee);
        }
        BigDecimal[] odd = FoodDeliveryService.split(new BigDecimal("33.33"));
        assertEquals(new BigDecimal("26.66"), odd[0]); // 26.664 rounds down
        assertEquals(new BigDecimal("6.67"), odd[1]);
    }

    @Test
    void allocationUsesTheOrdersFeeSnapshotAndIsRecordedOnce() {
        DeliveryRequest d = delivery(DeliveryStatus.DELIVERED);
        d.setEstimatedFee(new BigDecimal("999.00")); // must be ignored; the order snapshot is authoritative
        when(allocations.findByDeliveryRequestId("d1")).thenReturn(Optional.empty());

        DeliveryEarningsAllocation a = service.allocate(d).orElseThrow();

        assertEquals(new BigDecimal("25.00"), a.getDeliveryFeeSnapshot());
        assertEquals(new BigDecimal("20.00"), a.getDriverShare());
        assertEquals(new BigDecimal("5.00"), a.getPulaShare());
        assertEquals("PULA_RIDE_80_20", a.getSplitPolicy());
    }

    @Test
    void repeatAllocationReturnsTheExistingRowAndNeverADuplicate() {
        DeliveryEarningsAllocation existing = new DeliveryEarningsAllocation();
        when(allocations.findByDeliveryRequestId("d1")).thenReturn(Optional.of(existing));
        assertSame(existing, service.allocate(delivery(DeliveryStatus.DELIVERED)).orElseThrow());
        verify(allocations, never()).save(any());
    }

    @Test
    void noAllocationForOwnRidersCollectionRetailOrZeroFee() {
        when(allocations.findByDeliveryRequestId("d1")).thenReturn(Optional.empty());

        order.setFulfilmentMethod(FulfilmentOption.SELLER_DELIVERY);
        assertTrue(service.allocate(delivery(DeliveryStatus.DELIVERED)).isEmpty());

        order.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        order.setKind(OrderKind.RETAIL);
        assertTrue(service.allocate(delivery(DeliveryStatus.DELIVERED)).isEmpty());

        order.setKind(OrderKind.FOOD);
        order.setDeliveryFeeSnapshot(BigDecimal.ZERO);
        assertTrue(service.allocate(delivery(DeliveryStatus.DELIVERED)).isEmpty());
        order.setDeliveryFeeSnapshot(null);
        assertTrue(service.allocate(delivery(DeliveryStatus.DELIVERED)).isEmpty());
        verify(allocations, never()).save(any());
        assertFalse(FoodDeliveryService.needsPulaRideDelivery(retailLike()));
    }

    private Order retailLike() {
        Order o = new Order();
        o.setKind(OrderKind.RETAIL);
        o.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        return o;
    }
}
