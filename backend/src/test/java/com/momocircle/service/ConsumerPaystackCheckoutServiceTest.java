package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.MockPaystackGateway;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.StockOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** NOT VERIFIED IN THIS SANDBOX - no Maven network access. Run ./mvnw test locally. */
class ConsumerPaystackCheckoutServiceTest {

    private OrderRepository orders;
    private PaymentRepository payments;
    private CartService cart;
    private ProductService productService;
    private PaymentIdempotencyService idem;
    private MockPaystackGateway gateway;
    private ConsumerPaystackCheckoutService service;
    private User buyer;
    private Business business;
    private Payment payment;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        cart = mock(CartService.class);
        BusinessService bs = mock(BusinessService.class);
        productService = mock(ProductService.class);
        idem = mock(PaymentIdempotencyService.class);
        gateway = new MockPaystackGateway();
        service = new ConsumerPaystackCheckoutService(orders, payments, cart, bs, productService, idem,
                new PaymentOrchestrator(gateway, mock(PaymentProvider.class)), mock(FoodCheckoutService.class), "");

        buyer = new User();
        buyer.setEmail("b@example.com");
        business = new Business();
        business.setOwner(new User());
        when(bs.requireById(business.getId())).thenReturn(business);

        Product product = new Product();
        product.setName("Bread");
        product.setPrice(new BigDecimal("18.50"));
        product.setCurrency("ZAR");
        CartItem item = new CartItem();
        item.setProduct(product);
        item.setQuantity(4);
        when(cart.itemsForBusiness(buyer, business.getId())).thenReturn(List.of(item));

        payment = new Payment();
        when(idem.reserve(anyString(), any(), anyString(), anyString())).thenAnswer(i -> {
            payment.setAmount(i.getArgument(1)); // the real service stores the amount it is asked to charge
            payment.setCurrency(i.getArgument(2));
            return new PaymentIdempotencyService.Reservation(payment, false);
        });
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void chargesServerComputedTotalAndLeavesOrderProcessingNotPaid() {
        var s = service.start(buyer, business.getId(), "key-1");

        assertEquals(0, new BigDecimal("74.00").compareTo(s.amount()));
        assertEquals(OrderStatus.PAYMENT_PROCESSING, s.orderStatus());
        assertEquals(PaymentStatus.PROCESSING, s.paymentStatus());
        assertTrue(s.sandbox());
        assertEquals(PaymentPurpose.MARKET_PAYMENT, payment.getPurpose());
        verify(cart).clearForBusiness(buyer, business.getId());
    }

    @Test
    void idempotencyKeyIsScopedToTheBuyer() {
        service.start(buyer, business.getId(), "shared");
        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idem).reserve(key.capture(), any(), anyString(), anyString());
        assertTrue(key.getValue().startsWith("market:" + buyer.getId() + ":"));
    }

    @Test
    void emptyCartIsRejected() {
        when(cart.itemsForBusiness(buyer, business.getId())).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.start(buyer, business.getId(), "k"));
    }

    @Test
    void anotherUserCannotReadSomeoneElsesPaymentSession() {
        Order o = new Order();
        o.setBuyer(buyer);
        o.setPayment(payment);
        when(orders.findById(o.getId())).thenReturn(Optional.of(o));
        User other = new User();
        assertThrows(ApiException.class, () -> service.session(other, o.getId()));
    }
}
