package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.dto.SellerDashboardDtos.SellerDashboardResponse;
import com.momocircle.repository.DeliveryRequestRepository;
import com.momocircle.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class SellerDashboardServiceTest {

    private OrderRepository orderRepository;
    private BusinessService businessService;
    private ProductService productService;
    private DeliveryRequestRepository deliveryRequestRepository;
    private SellerDashboardService service;
    private User seller;
    private Business business;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        businessService = mock(BusinessService.class);
        productService = mock(ProductService.class);
        deliveryRequestRepository = mock(DeliveryRequestRepository.class);
        service = new SellerDashboardService(orderRepository, businessService, productService, deliveryRequestRepository);

        seller = new User();
        seller.setId("seller-1");
        business = new Business();
        business.setOwner(seller);
        when(businessService.requireOwn(seller)).thenReturn(business);
    }

    private Order paidOrder(User buyer, String total) {
        Order order = new Order();
        order.setBuyer(buyer);
        order.setBusiness(business);
        order.setStatus(OrderStatus.COMPLETED);
        order.setTotal(new BigDecimal(total));
        order.setCurrency("ZAR");
        order.setItems(List.of());
        order.setCreatedAt(Instant.now());
        return order;
    }

    @Test
    void countsUniqueCustomersNotTotalOrders() {
        User buyerA = new User();
        buyerA.setId("buyer-a");
        User buyerB = new User();
        buyerB.setId("buyer-b");

        // buyerA orders twice, buyerB orders once — 3 orders, 2 unique customers.
        when(orderRepository.findByBusinessId(business.getId())).thenReturn(
                List.of(paidOrder(buyerA, "100.00"), paidOrder(buyerA, "50.00"), paidOrder(buyerB, "75.00")));
        when(deliveryRequestRepository.findByOrderBusinessId(business.getId())).thenReturn(List.of());

        SellerDashboardResponse response = service.summary(seller);

        assertEquals(3, response.totalOrders());
        assertEquals(2, response.uniqueCustomers());
    }

    @Test
    void dailyRevenueCoversAFullFourteenDayWindowIncludingZeroDays() {
        when(orderRepository.findByBusinessId(business.getId())).thenReturn(List.of());
        when(deliveryRequestRepository.findByOrderBusinessId(business.getId())).thenReturn(List.of());

        SellerDashboardResponse response = service.summary(seller);

        assertEquals(14, response.dailyRevenue().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.dailyRevenue().get(0).amount()));
    }

    @Test
    void deliveryStatsCountByStatus() {
        when(orderRepository.findByBusinessId(business.getId())).thenReturn(List.of());

        DeliveryRequest delivered = new DeliveryRequest();
        delivered.setStatus(DeliveryStatus.DELIVERED);
        DeliveryRequest cancelled = new DeliveryRequest();
        cancelled.setStatus(DeliveryStatus.CANCELLED);
        DeliveryRequest inProgress = new DeliveryRequest();
        inProgress.setStatus(DeliveryStatus.ACCEPTED);
        when(deliveryRequestRepository.findByOrderBusinessId(business.getId()))
                .thenReturn(List.of(delivered, cancelled, inProgress));

        SellerDashboardResponse response = service.summary(seller);

        assertEquals(3, response.deliveryStats().requested());
        assertEquals(1, response.deliveryStats().delivered());
        assertEquals(1, response.deliveryStats().cancelled());
    }
}
