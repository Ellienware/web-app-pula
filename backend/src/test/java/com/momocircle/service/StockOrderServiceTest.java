package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.StockCartItem;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.StockDtos.PlaceStockOrderRequest;
import com.momocircle.dto.StockDtos.StockOrderResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.WholesaleProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven network access.
 * Run ./mvnw test locally before merging.
 */
class StockOrderServiceTest {

    private StockOrderRepository orderRepo;
    private StockCartService cartService;
    private BusinessService businessService;
    private WholesaleProductRepository productRepo;
    private StockDeliveryService deliveryService;
    private StockOrderService service;

    private User buyer;
    private Business buyerBusiness;
    private Business supplierBusiness;
    private WholesaleProduct product;

    @BeforeEach
    void setUp() {
        orderRepo = mock(StockOrderRepository.class);
        cartService = mock(StockCartService.class);
        businessService = mock(BusinessService.class);
        productRepo = mock(WholesaleProductRepository.class);
        deliveryService = mock(StockDeliveryService.class);
        service = new StockOrderService(orderRepo, cartService, businessService, productRepo, deliveryService);
        when(productRepo.reserve(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);

        buyer = new User();
        buyer.setId("buyer-user");

        buyerBusiness = new Business();
        buyerBusiness.setOwner(buyer);
        buyerBusiness.setName("Thandi's Spaza");
        buyerBusiness.setCategory("Grocery");
        buyerBusiness.setCapabilities(new java.util.LinkedHashSet<>(
                List.of(BusinessCapability.CAN_SELL_TO_CONSUMERS, BusinessCapability.CAN_BUY_STOCK)));

        supplierBusiness = new Business();
        supplierBusiness.setName("Cape Wholesale");
        supplierBusiness.setCategory("FMCG");
        supplierBusiness.setCapabilities(new java.util.LinkedHashSet<>(
                List.of(BusinessCapability.CAN_SUPPLY_BUSINESSES)));

        product = new WholesaleProduct();
        product.setSupplierBusiness(supplierBusiness);
        product.setName("2L Cooking Oil (case of 12)");
        product.setPackSize(12);
        product.setMoq(2);
        product.setPricePerPack(new BigDecimal("180.00"));
        product.setCurrency("ZAR");
        product.setAvailableStock(10);
        product.setActive(true);

        when(businessService.requireOwn(buyer)).thenReturn(buyerBusiness);
        when(orderRepo.save(any(StockOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.findByBuyerBusinessIdAndCheckoutGroupKey(any(), any())).thenReturn(List.of());
    }

    private StockCartItem cartItem(int qty) {
        StockCartItem i = new StockCartItem();
        i.setBuyerBusiness(buyerBusiness);
        i.setWholesaleProduct(product);
        i.setQuantityPacks(qty);
        return i;
    }

    @Test
    void checkoutCreatesOrderWithCorrectTotals() {
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(3)));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-1", StockFulfilmentOption.SELF_COLLECTION, null);

        List<StockOrderResponse> orders = service.checkout(buyer, req);

        assertEquals(1, orders.size());
        StockOrderResponse order = orders.get(0);
        // 3 packs × R180 = R540
        assertEquals(0, new BigDecimal("540.00").compareTo(order.subtotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(order.deliveryFee()));
        assertEquals(0, new BigDecimal("540.00").compareTo(order.total()));
        assertEquals(StockOrderStatus.PENDING_PAYMENT, order.status());
    }

    @Test
    void checkoutRejectsWhenMoqNotMet() {
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(1))); // MOQ is 2
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-2", StockFulfilmentOption.SELF_COLLECTION, null);

        assertThrows(ApiException.class, () -> service.checkout(buyer, req));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void checkoutRejectsWhenInsufficientStock() {
        product.setAvailableStock(1); // only 1 pack available
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(5)));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-3", StockFulfilmentOption.SELF_COLLECTION, null);

        assertThrows(ApiException.class, () -> service.checkout(buyer, req));
    }

    @Test
    void checkoutRequiresDeliveryAddressForNonCollection() {
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(3)));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-4", StockFulfilmentOption.WHOLESALER_DELIVERY, null);

        assertThrows(ApiException.class, () -> service.checkout(buyer, req));
    }

    @Test
    void checkoutIsIdempotentOnDuplicateKey() {
        StockOrder existing = new StockOrder();
        existing.setBuyerBusiness(buyerBusiness);
        existing.setSupplierBusiness(supplierBusiness);
        existing.setSubtotal(new BigDecimal("540.00"));
        existing.setDeliveryFeeSnapshot(BigDecimal.ZERO);
        existing.setTotal(new BigDecimal("540.00"));
        existing.setFulfilmentMethod(StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByBuyerBusinessIdAndCheckoutGroupKey(buyerBusiness.getId(), "idem-dup")).thenReturn(List.of(existing));

        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-dup", StockFulfilmentOption.SELF_COLLECTION, null);
        List<StockOrderResponse> result = service.checkout(buyer, req);

        assertEquals(1, result.size());
        verify(cartService, never()).rawItems(any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void checkoutGrouperBySupplierCreatesOneOrderPerSupplier() {
        Business supplier2 = new Business();
        supplier2.setName("Joburg Wholesale");
        supplier2.setCategory("FMCG");

        WholesaleProduct product2 = new WholesaleProduct();
        product2.setSupplierBusiness(supplier2);
        product2.setName("500g Candles (box of 24)");
        product2.setPackSize(24);
        product2.setMoq(1);
        product2.setPricePerPack(new BigDecimal("95.00"));
        product2.setCurrency("ZAR");
        product2.setAvailableStock(20);

        StockCartItem item1 = cartItem(2); // from supplierBusiness
        StockCartItem item2 = new StockCartItem();
        item2.setBuyerBusiness(buyerBusiness);
        item2.setWholesaleProduct(product2);
        item2.setQuantityPacks(1);

        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(item1, item2));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-multi", StockFulfilmentOption.SELF_COLLECTION, null);

        List<StockOrderResponse> orders = service.checkout(buyer, req);

        assertEquals(2, orders.size());
    }

    @Test
    void supplierCanAdvanceStatusFromPaidToConfirmed() {
        User supplierUser = new User();
        supplierBusiness.setOwner(supplierUser);
        when(businessService.requireOwn(supplierUser)).thenReturn(supplierBusiness);

        StockOrder order = new StockOrder();
        order.setBuyerBusiness(buyerBusiness);
        order.setSupplierBusiness(supplierBusiness);
        order.setStatus(StockOrderStatus.PAID);
        order.setSubtotal(BigDecimal.TEN);
        order.setDeliveryFeeSnapshot(BigDecimal.ZERO);
        order.setTotal(BigDecimal.TEN);
        order.setFulfilmentMethod(StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByIdAndSupplierBusinessId(order.getId(), supplierBusiness.getId()))
                .thenReturn(Optional.of(order));

        StockOrderResponse result = service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.CONFIRMED);

        assertEquals(StockOrderStatus.CONFIRMED, result.status());
    }
    // ---- PULA hardening tests ----

    @Test
    void idempotencyKeyIsScopedToTheBuyerBusiness() {
        // Another business already used "shared-key"; THIS buyer has not, so nothing is replayed
        // and a fresh order is created (the other business's order is never returned).
        when(orderRepo.findByBuyerBusinessIdAndCheckoutGroupKey(buyerBusiness.getId(), "shared-key")).thenReturn(List.of());
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(3)));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("shared-key", StockFulfilmentOption.SELF_COLLECTION, null);

        List<StockOrderResponse> orders = service.checkout(buyer, req);

        assertEquals(1, orders.size());
        verify(orderRepo, never()).findByIdempotencyKey(any());
    }

    @Test
    void checkoutFailsCleanlyWhenAtomicReservationLosesTheRace() {
        when(productRepo.reserve(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);
        when(cartService.rawItems(buyerBusiness)).thenReturn(List.of(cartItem(3)));
        PlaceStockOrderRequest req = new PlaceStockOrderRequest("idem-race", StockFulfilmentOption.SELF_COLLECTION, null);

        assertThrows(ApiException.class, () -> service.checkout(buyer, req));
        verify(orderRepo, never()).save(any());
    }

    private StockOrder orderWithStatus(StockOrderStatus status, StockFulfilmentOption method) {
        StockOrder order = new StockOrder();
        order.setBuyerBusiness(buyerBusiness);
        order.setSupplierBusiness(supplierBusiness);
        order.setStatus(status);
        order.setSubtotal(BigDecimal.TEN);
        order.setDeliveryFeeSnapshot(BigDecimal.ZERO);
        order.setTotal(BigDecimal.TEN);
        order.setFulfilmentMethod(method);
        return order;
    }

    @Test
    void supplierCannotMarkAnOrderPaidThemselves() {
        User supplierUser = new User();
        supplierBusiness.setOwner(supplierUser);
        when(businessService.requireOwn(supplierUser)).thenReturn(supplierBusiness);
        StockOrder order = orderWithStatus(StockOrderStatus.PENDING_PAYMENT, StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByIdAndSupplierBusinessId(order.getId(), supplierBusiness.getId())).thenReturn(Optional.of(order));

        assertThrows(ApiException.class, () -> service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.PAID));
        assertThrows(ApiException.class, () -> service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.PAYMENT_PROCESSING));
        assertEquals(StockOrderStatus.PENDING_PAYMENT, order.getStatus());
    }

    @Test
    void supplierCannotMarkDeliveryOrderDeliveredDirectly() {
        User supplierUser = new User();
        supplierBusiness.setOwner(supplierUser);
        when(businessService.requireOwn(supplierUser)).thenReturn(supplierBusiness);
        StockOrder order = orderWithStatus(StockOrderStatus.READY_FOR_COLLECTION, StockFulfilmentOption.PULA_RIDE_DELIVERY);
        when(orderRepo.findByIdAndSupplierBusinessId(order.getId(), supplierBusiness.getId())).thenReturn(Optional.of(order));

        assertThrows(ApiException.class, () -> service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.DELIVERED));
        assertThrows(ApiException.class, () -> service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.OUT_FOR_DELIVERY));
    }

    @Test
    void readyForCollectionOnDeliveryOrderDispatchesToPulaRide() {
        User supplierUser = new User();
        supplierBusiness.setOwner(supplierUser);
        when(businessService.requireOwn(supplierUser)).thenReturn(supplierBusiness);
        StockOrder order = orderWithStatus(StockOrderStatus.PREPARING, StockFulfilmentOption.WHOLESALER_DELIVERY);
        when(orderRepo.findByIdAndSupplierBusinessId(order.getId(), supplierBusiness.getId())).thenReturn(Optional.of(order));

        service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.READY_FOR_COLLECTION);

        verify(deliveryService).requireDispatchable(order);
        verify(deliveryService).dispatch(order);
    }

    @Test
    void selfCollectionOrderDoesNotDispatchAndSupplierCompletesIt() {
        User supplierUser = new User();
        supplierBusiness.setOwner(supplierUser);
        when(businessService.requireOwn(supplierUser)).thenReturn(supplierBusiness);
        StockOrder order = orderWithStatus(StockOrderStatus.READY_FOR_COLLECTION, StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByIdAndSupplierBusinessId(order.getId(), supplierBusiness.getId())).thenReturn(Optional.of(order));

        StockOrderResponse r = service.advanceStatus(supplierUser, order.getId(), StockOrderStatus.DELIVERED);

        assertEquals(StockOrderStatus.DELIVERED, r.status());
        verify(deliveryService, never()).dispatch(any());
    }

    @Test
    void buyerCancelReturnsReservedStockOnlyWhileUnpaid() {
        StockOrder unpaid = orderWithStatus(StockOrderStatus.PENDING_PAYMENT, StockFulfilmentOption.SELF_COLLECTION);
        com.momocircle.domain.StockOrderItem item = new com.momocircle.domain.StockOrderItem();
        item.setWholesaleProduct(product);
        item.setQuantityPacks(3);
        unpaid.getItems().add(item);
        when(orderRepo.findByIdAndBuyerBusinessId(unpaid.getId(), buyerBusiness.getId())).thenReturn(Optional.of(unpaid));

        service.cancelByBuyer(buyer, unpaid.getId());

        assertEquals(StockOrderStatus.CANCELLED, unpaid.getStatus());
        verify(productRepo).release(product.getId(), 3);

        StockOrder paid = orderWithStatus(StockOrderStatus.PAID, StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByIdAndBuyerBusinessId(paid.getId(), buyerBusiness.getId())).thenReturn(Optional.of(paid));
        assertThrows(ApiException.class, () -> service.cancelByBuyer(buyer, paid.getId()));
    }
}
