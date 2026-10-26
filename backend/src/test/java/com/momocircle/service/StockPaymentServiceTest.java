package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.Payment;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.MockPaystackGateway;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.StockOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT VERIFIED IN THIS SANDBOX — no Maven network access. Run ./mvnw test locally. */
class StockPaymentServiceTest {

    private StockOrderRepository orderRepo;
    private PaymentRepository paymentRepo;
    private MockPaystackGateway gateway;
    private StockOrderService stockOrderService;
    private StockPaymentService service;
    private User buyer;
    private Business buyerBusiness;
    private StockOrder order;

    @BeforeEach
    void setUp() {
        orderRepo = mock(StockOrderRepository.class);
        paymentRepo = mock(PaymentRepository.class);
        gateway = new MockPaystackGateway();
        stockOrderService = mock(StockOrderService.class);
        BusinessService businessService = mock(BusinessService.class);
        NotificationService notifications = mock(NotificationService.class);
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(gateway, mock(PaymentProvider.class));
        service = new StockPaymentService(orderRepo, paymentRepo, orchestrator, businessService,
                stockOrderService, notifications, "", 30, 120);

        buyer = new User();
        buyer.setId("buyer");
        buyer.setEmail("buyer@example.com");
        buyerBusiness = new Business();
        buyerBusiness.setOwner(buyer);
        Business supplier = new Business();
        supplier.setOwner(new User());
        when(businessService.requireOwn(buyer)).thenReturn(buyerBusiness);

        order = new StockOrder();
        order.setBuyerBusiness(buyerBusiness);
        order.setSupplierBusiness(supplier);
        order.setTotal(new BigDecimal("540.00"));
        order.setSubtotal(new BigDecimal("540.00"));
        order.setDeliveryFeeSnapshot(BigDecimal.ZERO);
        order.setCurrency("ZAR");
        order.setFulfilmentMethod(StockFulfilmentOption.SELF_COLLECTION);
        when(orderRepo.findByIdAndBuyerBusinessId(order.getId(), buyerBusiness.getId())).thenReturn(Optional.of(order));
        when(orderRepo.findByPaymentId(any())).thenReturn(Optional.of(order));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.findByProviderReference(any())).thenAnswer(inv ->
                Optional.ofNullable(order.getPayment()));
        // confirmByReference loads the payment under a row lock (so concurrent confirmations cannot both fulfil an order)
        when(paymentRepo.findByProviderReferenceForUpdate(any())).thenAnswer(inv ->
                Optional.ofNullable(order.getPayment()));
    }

    @Test
    void startChargesTheServerSideOrderTotalAndDoesNotMarkPaid() {
        var session = service.start(buyer, order.getId());

        assertEquals(0, new BigDecimal("540.00").compareTo(session.amount()));
        assertEquals(PaymentStatus.PROCESSING, session.paymentStatus());
        assertEquals(StockOrderStatus.PAYMENT_PROCESSING, order.getStatus());
        assertTrue(session.sandbox());
        assertEquals(PaymentPurpose.MARKET_PAYMENT, order.getPayment().getPurpose());
        assertEquals("paystack-mock", order.getPayment().getProvider());
    }

    @Test
    void startingTwiceResumesTheSamePaymentInsteadOfCreatingASecond() {
        service.start(buyer, order.getId());
        Payment first = order.getPayment();
        service.start(buyer, order.getId());

        assertSame(first, order.getPayment());
        verify(paymentRepo, times(1)).save(any(Payment.class));
    }

    @Test
    void orderIsNotPaidUntilProviderConfirmsSuccess() {
        var session = service.start(buyer, order.getId());

        service.confirmByReference(session.reference()); // provider still PENDING

        assertEquals(StockOrderStatus.PAYMENT_PROCESSING, order.getStatus());
        assertEquals(PaymentStatus.PROCESSING, order.getPayment().getStatus());
    }

    @Test
    void confirmedPaymentMarksOrderPaidAndIsIdempotent() {
        var session = service.start(buyer, order.getId());
        gateway.simulateCustomerPaid(session.reference());

        service.confirmByReference(session.reference());
        service.confirmByReference(session.reference()); // replay: no-op

        assertEquals(StockOrderStatus.PAID, order.getStatus());
        assertEquals(PaymentStatus.SUCCESSFUL, order.getPayment().getStatus());
    }

    @Test
    void amountMismatchIsNeverMarkedPaid() {
        var session = service.start(buyer, order.getId());
        gateway.simulateCustomerPaid(session.reference());
        order.getPayment().setAmount(new BigDecimal("999.00")); // provider says 540.00

        service.confirmByReference(session.reference());

        assertEquals(StockOrderStatus.PAYMENT_PROCESSING, order.getStatus());
        assertTrue(order.getPayment().getFailureReason().startsWith("REVIEW:"));
    }

    @Test
    void anotherBusinessCannotStartPaymentForThisOrder() {
        User intruder = new User();
        intruder.setId("intruder");
        Business other = new Business();
        other.setOwner(intruder);
        BusinessService bs = mock(BusinessService.class);
        when(bs.requireOwn(intruder)).thenReturn(other);
        StockPaymentService svc = new StockPaymentService(orderRepo, paymentRepo,
                new PaymentOrchestrator(gateway, mock(PaymentProvider.class)), bs, stockOrderService,
                mock(NotificationService.class), "", 30, 120);

        assertThrows(ApiException.class, () -> svc.start(intruder, order.getId()));
    }
    @Test
    void largeOrderNeedsExplicitAcknowledgementBeforePaymentStarts() {
        order.setTotal(new BigDecimal("15000.00"));

        ApiException ex = assertThrows(ApiException.class, () -> service.start(buyer, order.getId()));
        assertTrue(ex.getMessage().startsWith(StockPaymentService.RISK_PREFIX));
        assertNull(order.getPayment());

        var session = service.start(buyer, order.getId(), true);
        assertEquals(PaymentStatus.PROCESSING, session.paymentStatus());
    }
}
