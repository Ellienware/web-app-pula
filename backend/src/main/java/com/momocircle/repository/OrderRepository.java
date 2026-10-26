package com.momocircle.repository;

import com.momocircle.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
    Page<Order> findByBuyerIdOrderByCreatedAtDesc(String buyerId, Pageable pageable);
    Page<Order> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    /**
     * Kept the same name/signature callers already use (OrderService,
     * PaymentReconciliationService) even though `payment` is now a real
     * relationship rather than a raw string column.
     */
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.payment.id = :paymentId")
    java.util.Optional<Order> findByPaymentId(@org.springframework.data.repository.query.Param("paymentId") String paymentId);

    java.util.List<Order> findByBusinessId(String businessId);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.total), 0) from Order o where o.status in :statuses")
    java.math.BigDecimal sumTotalByStatusIn(
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.momocircle.domain.enums.OrderStatus> statuses);

    /**
     * Phase 8 — total consumer sales revenue for a seller business in a period
     * (COMPLETED orders only — PENDING_PAYMENT / CANCELLED orders don't count as income).
     */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.total), 0) from Order o "
            + "where o.business.id = :businessId "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.COMPLETED "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumSalesForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Phase 8 — recent completed sales for the cash-flow detail ledger. */
    @org.springframework.data.jpa.repository.Query(
            "select o from Order o "
            + "where o.business.id = :businessId "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.COMPLETED "
            + "and o.createdAt >= :from and o.createdAt < :to "
            + "order by o.createdAt desc")
    java.util.List<Order> findCompletedByBusinessInPeriod(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** PULA Food: paid orders the restaurant has not answered by their deadline. */
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.PAID "
            + "and o.restaurantDecision = com.momocircle.domain.enums.RestaurantDecision.PENDING "
            + "and o.acceptDeadlineAt < :now")
    java.util.List<Order> findFoodOrdersPastAcceptDeadline(@org.springframework.data.repository.query.Param("now") java.time.Instant now);

    /** Row-locked load used by the restaurant decision flow so accept, reject and timeout cannot race. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    java.util.Optional<Order> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    /** PULA Food: orders in REFUND_PENDING whose refund still has to be started or driven (Paystack and legacy alike). */
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.REFUND_PENDING "
            + "and o.payment.status in (com.momocircle.domain.enums.PaymentStatus.SUCCESSFUL, com.momocircle.domain.enums.PaymentStatus.REFUNDED)")
    java.util.List<Order> findFoodRefundsToRetry();

    /** PULA Food: food sales (item totals incl. modifiers, excluding any delivery fee) on completed food orders. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.subtotal), 0) from Order o where o.business.id = :businessId "
            + "and o.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.COMPLETED "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumFoodSalesForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** PULA Food: PULA Ride delivery fees charged on completed food orders. Collected for the delivery, NOT restaurant income. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.deliveryFeeSnapshot), 0) from Order o where o.business.id = :businessId "
            + "and o.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and o.fulfilmentMethod = com.momocircle.domain.enums.FulfilmentOption.MOMORIDE_DELIVERY "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.COMPLETED "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumPulaRideDeliveryFeesForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** PULA Food: delivery fees the restaurant keeps for its own (non-PULA Ride) delivery. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.deliveryFeeSnapshot), 0) from Order o where o.business.id = :businessId "
            + "and o.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and o.fulfilmentMethod in (com.momocircle.domain.enums.FulfilmentOption.SELLER_DELIVERY, "
            + "com.momocircle.domain.enums.FulfilmentOption.SUPPORTED_PARTNER_DELIVERY) "
            + "and o.status = com.momocircle.domain.enums.OrderStatus.COMPLETED "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumOwnDeliveryFeesForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Newest sales whose customer payment was provider-confirmed: the population reconciliation checks. */
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.payment is not null and o.payment.status = "
            + "com.momocircle.domain.enums.PaymentStatus.SUCCESSFUL order by o.createdAt desc")
    java.util.List<Order> findRecentPaid(org.springframework.data.domain.Pageable pageable);

    Page<Order> findByBranchIdOrderByCreatedAtDesc(String branchId, Pageable pageable);

    /** Parent-business report: revenue-status orders per branch (a null branch id is the "no branch" bucket). */
    @org.springframework.data.jpa.repository.Query("select b.id, count(o), coalesce(sum(o.total), 0) from Order o left join o.branch b "
            + "where o.business.id = :businessId and o.status in :statuses and o.createdAt >= :from and o.createdAt < :to group by b.id")
    java.util.List<Object[]> salesByBranch(@org.springframework.data.repository.query.Param("businessId") String businessId,
                                           @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.momocircle.domain.enums.OrderStatus> statuses,
                                           @org.springframework.data.repository.query.Param("from") java.time.Instant from,
                                           @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    @org.springframework.data.jpa.repository.Query("select b.id, coalesce(sum(i.quantity), 0) from Order o join o.items i left join o.branch b "
            + "where o.business.id = :businessId and o.status in :statuses and o.createdAt >= :from and o.createdAt < :to group by b.id")
    java.util.List<Object[]> unitsByBranch(@org.springframework.data.repository.query.Param("businessId") String businessId,
                                           @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.momocircle.domain.enums.OrderStatus> statuses,
                                           @org.springframework.data.repository.query.Param("from") java.time.Instant from,
                                           @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
