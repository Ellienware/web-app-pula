package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.dto.DeliveryDtos.DeliveryRequestResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryRequestRepository;
import com.momocircle.repository.momoride.DriverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class DeliveryRequestServiceTest {

    private DeliveryRequestRepository deliveryRequestRepository;
    private OrderService orderService;
    private DriverRepository driverRepository;
    private NotificationService notificationService;
    private DeliveryAcceptanceConcurrencyService acceptanceConcurrencyService;
    private StockOrderService stockOrderService;
    private PayoutService payoutService;
    private com.momocircle.repository.DeliveryPoolMemberRepository poolMemberRepository;
    private DeliveryRequestService service;
    private FoodDeliveryService foodDeliveryService;

    private User sellerUser;
    private User otherUser;
    private User driverUser;
    private Business business;
    private Order order;

    @BeforeEach
    void setUp() {
        deliveryRequestRepository = mock(DeliveryRequestRepository.class);
        orderService = mock(OrderService.class);
        driverRepository = mock(DriverRepository.class);
        notificationService = mock(NotificationService.class);
        acceptanceConcurrencyService = mock(DeliveryAcceptanceConcurrencyService.class);
        stockOrderService = mock(StockOrderService.class);
        payoutService = mock(PayoutService.class);
        poolMemberRepository = mock(com.momocircle.repository.DeliveryPoolMemberRepository.class);
        foodDeliveryService = mock(FoodDeliveryService.class);
        service = new DeliveryRequestService(deliveryRequestRepository, orderService, driverRepository,
                notificationService, acceptanceConcurrencyService, stockOrderService, payoutService, poolMemberRepository,
                foodDeliveryService);

        sellerUser = new User();
        sellerUser.setId("seller-1");
        otherUser = new User();
        otherUser.setId("other-1");
        driverUser = new User();
        driverUser.setId("driver-user-1");

        business = new Business();
        business.setOwner(sellerUser);
        business.setName("Thandi's Spaza");
        business.setLocation("Durban");
        Set<FulfilmentOption> options = new LinkedHashSet<>();
        options.add(FulfilmentOption.MOMORIDE_DELIVERY);
        business.setFulfilmentOptions(options);

        order = new Order();
        order.setBusiness(business);
        order.setBuyer(otherUser);
        order.setCurrency("ZAR");
        order.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        order.setDeliveryAddress("12 Main Road, Durban");

        when(deliveryRequestRepository.save(any(DeliveryRequest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void requestSucceedsForOrderOwnerWithMomoRideFulfilment() {
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        when(deliveryRequestRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());

        DeliveryRequestResponse response = service.request(sellerUser, order.getId());

        assertEquals(DeliveryStatus.REQUESTED, response.status());
        assertEquals("Durban", response.pickupLocation());
        assertEquals("12 Main Road, Durban", response.dropoffLocation());
    }

    @Test
    void requestRejectsNonOwner() {
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        assertThrows(ApiException.class, () -> service.request(otherUser, order.getId()));
    }

    @Test
    void requestRejectsOrderNotSetToMomoRideDelivery() {
        order.setFulfilmentMethod(FulfilmentOption.CUSTOMER_PICKUP);
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        assertThrows(ApiException.class, () -> service.request(sellerUser, order.getId()));
    }

    @Test
    void requestRejectsDuplicateForSameOrder() {
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        when(deliveryRequestRepository.findByOrderId(order.getId()))
                .thenReturn(Optional.of(new DeliveryRequest()));
        assertThrows(ApiException.class, () -> service.request(sellerUser, order.getId()));
    }

    @Test
    void markPickedUpRejectsDriverNotAssignedToThisDelivery() {
        Driver assignedDriver = new Driver();
        assignedDriver.setId("driver-assigned");
        Driver otherDriver = new Driver();
        otherDriver.setId("driver-other");

        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setDriver(assignedDriver);
        delivery.setStatus(DeliveryStatus.ACCEPTED);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(otherDriver));
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        assertThrows(ApiException.class, () -> service.markPickedUp(driverUser, "delivery-1"));
    }

    @Test
    void markDeliveredCompletesTheUnderlyingOrder() {
        Driver driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);

        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.PICKED_UP);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        DeliveryRequestResponse response = service.markDelivered(driverUser, "delivery-1");

        assertEquals(DeliveryStatus.DELIVERED, response.status());
    }

    @Test
    void cannotSkipFromRequestedStraightToDelivered() {
        Driver driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);

        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.REQUESTED); // not yet ACCEPTED or PICKED_UP

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        assertThrows(ApiException.class, () -> service.markDelivered(driverUser, "delivery-1"));
    }

    @Test
    void sellerCanCancelBeforePickup() {
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setStatus(DeliveryStatus.REQUESTED);

        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        DeliveryRequestResponse response = service.cancel(sellerUser, "delivery-1");
        assertEquals(DeliveryStatus.CANCELLED, response.status());
    }

    @Test
    void unrelatedUserCannotCancel() {
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setStatus(DeliveryStatus.REQUESTED);

        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        assertThrows(ApiException.class, () -> service.cancel(otherUser, "delivery-1"));
    }

    @Test
    void buyerCanViewTheirOwnOrdersDelivery() {
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setStatus(DeliveryStatus.ACCEPTED);

        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        when(deliveryRequestRepository.findByOrderId(order.getId())).thenReturn(Optional.of(delivery));

        DeliveryRequestResponse response = service.getForOrder(otherUser, order.getId()); // otherUser is the buyer
        assertEquals(DeliveryStatus.ACCEPTED, response.status());
    }

    @Test
    void unrelatedUserCannotViewAnOrdersDelivery() {
        User stranger = new User();
        stranger.setId("stranger-1");
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);

        assertThrows(ApiException.class, () -> service.getForOrder(stranger, order.getId()));
    }

    @Test
    void viewingDeliveryForAnOrderWithNoneRequestedYetIsNotFound() {
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        when(deliveryRequestRepository.findByOrderId(order.getId())).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.getForOrder(sellerUser, order.getId()));
    }
    // ---- PULA: pools, stock deliveries, payouts ----

    private com.momocircle.domain.StockOrder stockOrder() {
        Business supplier = new Business();
        supplier.setOwner(sellerUser);
        supplier.setName("Cape Wholesale");
        Business buyerBiz = new Business();
        buyerBiz.setOwner(otherUser);
        buyerBiz.setName("Thandi's Spaza");
        com.momocircle.domain.StockOrder so = new com.momocircle.domain.StockOrder();
        so.setSupplierBusiness(supplier);
        so.setBuyerBusiness(buyerBiz);
        so.setCurrency("ZAR");
        so.setDeliveryFeeSnapshot(new java.math.BigDecimal("50.00"));
        return so;
    }

    @Test
    void poolReservedDeliveryCannotBeAcceptedByNonMember() {
        Driver outsider = new Driver();
        outsider.setId("outsider");
        outsider.setUser(driverUser);
        com.momocircle.domain.StockOrder so = stockOrder();
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setStockOrder(so);
        delivery.setPoolBusiness(so.getSupplierBusiness());
        delivery.setStatus(DeliveryStatus.REQUESTED);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(outsider));
        when(deliveryRequestRepository.findById("d-pool")).thenReturn(Optional.of(delivery));
        when(poolMemberRepository.existsByBusinessIdAndDriverIdAndActiveTrue(
                so.getSupplierBusiness().getId(), "outsider")).thenReturn(false);

        assertThrows(ApiException.class, () -> service.accept(driverUser, "d-pool"));
        org.mockito.Mockito.verify(acceptanceConcurrencyService, org.mockito.Mockito.never())
                .acceptWithRetry(any(), any());
    }

    @Test
    void poolMemberCanAcceptPoolReservedDelivery() {
        Driver member = new Driver();
        member.setId("member");
        member.setUser(driverUser);
        com.momocircle.domain.StockOrder so = stockOrder();
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setStockOrder(so);
        delivery.setPoolBusiness(so.getSupplierBusiness());
        delivery.setStatus(DeliveryStatus.ACCEPTED);
        delivery.setDriver(member);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(member));
        when(deliveryRequestRepository.findById("d-pool")).thenReturn(Optional.of(delivery));
        when(poolMemberRepository.existsByBusinessIdAndDriverIdAndActiveTrue(
                so.getSupplierBusiness().getId(), "member")).thenReturn(true);

        service.accept(driverUser, "d-pool");

        org.mockito.Mockito.verify(acceptanceConcurrencyService).acceptWithRetry("d-pool", "member");
    }

    @Test
    void completingStockDeliveryMarksStockOrderDeliveredAndCreatesPayout() {
        Driver driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);
        com.momocircle.domain.StockOrder so = stockOrder();
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setStockOrder(so);
        delivery.setDriver(driver);
        delivery.setEstimatedFee(so.getDeliveryFeeSnapshot());
        delivery.setStatus(DeliveryStatus.PICKED_UP);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("d-stock")).thenReturn(Optional.of(delivery));
        when(deliveryRequestRepository.save(any(DeliveryRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markDelivered(driverUser, "d-stock");

        org.mockito.Mockito.verify(stockOrderService).markDeliveredByDelivery(so);
        org.mockito.Mockito.verify(payoutService).createForCompletedDelivery(delivery);
        org.mockito.Mockito.verify(orderService, org.mockito.Mockito.never()).markCompletedByDelivery(any());
    }

    @Test
    void pickingUpStockDeliveryMarksStockOrderOutForDelivery() {
        Driver driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);
        com.momocircle.domain.StockOrder so = stockOrder();
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setStockOrder(so);
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.ACCEPTED);

        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("d-stock")).thenReturn(Optional.of(delivery));
        when(deliveryRequestRepository.save(any(DeliveryRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPickedUp(driverUser, "d-stock");

        org.mockito.Mockito.verify(stockOrderService).markOutForDelivery(so);
    }

    // ------------------------------------------------------------------ PULA Food

    private Driver driverFor(User u, String id) {
        Driver d = new Driver();
        d.setId(id);
        d.setUser(u);
        return d;
    }

    @Test
    void foodDeliveryPaysTheDriversEightyPercentShareNotTheFullFee() {
        order.setKind(com.momocircle.domain.enums.OrderKind.FOOD);
        order.setDeliveryFeeSnapshot(new java.math.BigDecimal("25.00"));
        Driver driver = driverFor(driverUser, "driver-1");
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        com.momocircle.domain.DeliveryEarningsAllocation alloc = new com.momocircle.domain.DeliveryEarningsAllocation();
        alloc.setDriverShare(new java.math.BigDecimal("20.00"));
        alloc.setPulaShare(new java.math.BigDecimal("5.00"));
        when(foodDeliveryService.allocate(delivery)).thenReturn(Optional.of(alloc));
        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        service.markDelivered(driverUser, "delivery-1");

        verify(orderService).markCompletedByDelivery(order);
        verify(payoutService).createForCompletedDelivery(delivery, new java.math.BigDecimal("20.00"));
        verify(payoutService, never()).createForCompletedDelivery(delivery);
    }

    @Test
    void retailDeliveryNeverTouchesFoodAllocation() {
        Driver driver = driverFor(driverUser, "driver-1");
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order); // RETAIL by default
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        when(driverRepository.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        service.markDelivered(driverUser, "delivery-1");

        verify(foodDeliveryService, never()).allocate(any());
        verify(payoutService, never()).createForCompletedDelivery(any(DeliveryRequest.class), any());
    }

    @Test
    void assignedDriverWithdrawingFromAFoodDeliveryReoffersItInsteadOfCancelling() {
        order.setKind(com.momocircle.domain.enums.OrderKind.FOOD);
        Driver driver = driverFor(driverUser, "driver-1");
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setDriver(driver);
        delivery.setStatus(DeliveryStatus.ACCEPTED);
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));
        when(foodDeliveryService.reofferAfterDriverWithdrawal(delivery)).thenAnswer(inv -> {
            delivery.setStatus(DeliveryStatus.REQUESTED);
            delivery.setDriver(null);
            return delivery;
        });

        DeliveryRequestResponse r = service.cancel(driverUser, "delivery-1");

        assertEquals(DeliveryStatus.REQUESTED, r.status());
        verify(foodDeliveryService).reofferAfterDriverWithdrawal(delivery);
    }

    @Test
    void sellerCannotCancelAnAutoDispatchedFoodDelivery() {
        order.setKind(com.momocircle.domain.enums.OrderKind.FOOD);
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(order);
        delivery.setStatus(DeliveryStatus.REQUESTED);
        when(deliveryRequestRepository.findById("delivery-1")).thenReturn(Optional.of(delivery));

        assertThrows(ApiException.class, () -> service.cancel(sellerUser, "delivery-1"));

        assertEquals(DeliveryStatus.REQUESTED, delivery.getStatus());
        verify(foodDeliveryService, never()).reofferAfterDriverWithdrawal(any());
    }

    @Test
    void sellerCannotManuallyRequestDeliveryForAFoodOrder() {
        order.setKind(com.momocircle.domain.enums.OrderKind.FOOD);
        order.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        order.setDeliveryAddress("5 Main Rd");
        when(orderService.requireOrderEntity(order.getId())).thenReturn(order);
        assertThrows(ApiException.class, () -> service.request(sellerUser, order.getId()));
        verify(deliveryRequestRepository, never()).save(any());
    }
}
