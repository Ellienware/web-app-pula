package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.dto.MarketDtos.ProductResponse;
import com.momocircle.dto.SellerDashboardDtos.DailyRevenuePoint;
import com.momocircle.dto.SellerDashboardDtos.DeliveryStats;
import com.momocircle.dto.SellerDashboardDtos.SellerDashboardResponse;
import com.momocircle.repository.DeliveryRequestRepository;
import com.momocircle.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes seller-facing aggregates on the fly from Order/OrderItem/
 * DeliveryRequest rows. Deliberately simple (in-memory aggregation, not a
 * reporting warehouse) — appropriate for a single seller's own order
 * volume, not a platform-wide report.
 */
@Service
public class SellerDashboardService {

    private static final int TREND_DAYS = 14;

    /** Orders that represent real, collected revenue. Cancelled/pending-payment orders never count. */
    private static final Set<OrderStatus> REVENUE_STATUSES = EnumSet.of(
            OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.READY,
            OrderStatus.COMPLETED, OrderStatus.REFUND_PENDING);

    private final OrderRepository orderRepository;
    private final BusinessService businessService;
    private final ProductService productService;
    private final DeliveryRequestRepository deliveryRequestRepository;

    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.PaymentRefundRepository refundRepository;

    public SellerDashboardService(OrderRepository orderRepository, BusinessService businessService,
                                   ProductService productService,
                                   DeliveryRequestRepository deliveryRequestRepository) {
        this.orderRepository = orderRepository;
        this.businessService = businessService;
        this.productService = productService;
        this.deliveryRequestRepository = deliveryRequestRepository;
    }

    @Transactional(readOnly = true)
    public SellerDashboardResponse summary(User seller) {
        Business business = businessService.requireOwn(seller);
        List<Order> orders = orderRepository.findByBusinessId(business.getId());

        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfWeek = startOfToday.minusSeconds(7L * 24 * 3600);
        Instant startOfMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        LocalDate trendStart = LocalDate.now(ZoneOffset.UTC).minusDays(TREND_DAYS - 1L);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal today = BigDecimal.ZERO;
        BigDecimal week = BigDecimal.ZERO;
        BigDecimal month = BigDecimal.ZERO;
        long pending = 0;
        long completed = 0;
        long cancelled = 0;
        Map<String, Integer> soldQtyByProduct = new LinkedHashMap<>();
        Set<String> uniqueBuyerIds = new HashSet<>();
        Map<LocalDate, BigDecimal> revenueByDay = new LinkedHashMap<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            revenueByDay.put(trendStart.plusDays(i), BigDecimal.ZERO);
        }

        for (Order order : orders) {
            if (REVENUE_STATUSES.contains(order.getStatus())) {
                // Revenue is the order total NET of refunds that have actually completed (a partial refund reduces it, a
                // pending or failed refund does not). Each sale is counted once from the order record: the settlement and the
                // simulated wallet credit for the same sale are other representations of it and are never added on top.
                BigDecimal net = netOfCompletedRefunds(order);
                total = total.add(net);
                if (!order.getCreatedAt().isBefore(startOfToday)) today = today.add(net);
                if (!order.getCreatedAt().isBefore(startOfWeek)) week = week.add(net);
                if (!order.getCreatedAt().isBefore(startOfMonth)) month = month.add(net);
                for (OrderItem item : order.getItems()) {
                    soldQtyByProduct.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
                }
                uniqueBuyerIds.add(order.getBuyer().getId());

                if (order.getCreatedAt() != null) {
                    LocalDate orderDate = order.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    if (!orderDate.isBefore(trendStart)) {
                        revenueByDay.merge(orderDate, netOfCompletedRefunds(order), BigDecimal::add);
                    }
                }
            }
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
                pending++;
            } else if (order.getStatus() == OrderStatus.COMPLETED) {
                completed++;
            } else if (order.getStatus() == OrderStatus.CANCELLED) {
                cancelled++;
            }
        }

        List<ProductResponse> topProducts = soldQtyByProduct.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(5)
                .map(e -> productService.get(e.getKey()))
                .toList();

        List<DailyRevenuePoint> dailyRevenue = new ArrayList<>();
        revenueByDay.forEach((date, amount) -> dailyRevenue.add(new DailyRevenuePoint(date, amount)));

        List<DeliveryRequest> deliveries = deliveryRequestRepository.findByOrderBusinessId(business.getId());
        long requested = deliveries.size();
        long delivered = deliveries.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED).count();
        long deliveryCancelled = deliveries.stream().filter(d -> d.getStatus() == DeliveryStatus.CANCELLED).count();
        DeliveryStats deliveryStats = new DeliveryStats(requested, delivered, deliveryCancelled);

        String currency = orders.isEmpty() ? null : orders.get(0).getCurrency();
        return new SellerDashboardResponse(total, today, week, month, orders.size(), pending, completed, cancelled,
                topProducts, currency, uniqueBuyerIds.size(), dailyRevenue, deliveryStats);
    }

    private BigDecimal netOfCompletedRefunds(Order order) {
        if (refundRepository == null) return order.getTotal();
        BigDecimal refunded = refundRepository.sumRefundedByOrder(order.getId());
        if (refunded == null || refunded.signum() <= 0) return order.getTotal();
        return order.getTotal().subtract(refunded).max(BigDecimal.ZERO);
    }
}
