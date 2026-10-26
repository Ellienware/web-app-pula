package com.momocircle.repository;

import com.momocircle.domain.StockOrder;
import com.momocircle.domain.enums.StockOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockOrderRepository extends JpaRepository<StockOrder, String> {
    Page<StockOrder> findByBuyerBusinessIdOrderByCreatedAtDesc(String buyerBusinessId, Pageable pageable);
    Page<StockOrder> findBySupplierBusinessIdOrderByCreatedAtDesc(String supplierBusinessId, Pageable pageable);
    Page<StockOrder> findBySupplierBusinessIdAndStatusOrderByCreatedAtDesc(String supplierBusinessId, StockOrderStatus status, Pageable pageable);
    Optional<StockOrder> findByIdAndBuyerBusinessId(String id, String buyerBusinessId);
    Optional<StockOrder> findByIdAndSupplierBusinessId(String id, String supplierBusinessId);
    Optional<StockOrder> findByIdempotencyKey(String idempotencyKey);
    List<StockOrder> findByBuyerBusinessIdAndCheckoutGroupKey(String buyerBusinessId, String checkoutGroupKey);
    Optional<StockOrder> findByPaymentId(String paymentId);
    long countByBuyerBusinessIdAndStatus(String buyerBusinessId, StockOrderStatus status);
    List<StockOrder> findTop20ByBuyerBusinessIdOrderByCreatedAtDesc(String buyerBusinessId);
    List<StockOrder> findByStatusAndCreatedAtBefore(StockOrderStatus status, Instant before);

    /**
     * Phase 8 — total stock procurement spend for a buyer business in a period.
     */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.total), 0) from StockOrder o "
            + "where o.buyerBusiness.id = :businessId "
            + "and o.status not in ("
            + "  com.momocircle.domain.enums.StockOrderStatus.PENDING_PAYMENT,"
            + "  com.momocircle.domain.enums.StockOrderStatus.PAYMENT_PROCESSING,"
            + "  com.momocircle.domain.enums.StockOrderStatus.CANCELLED"
            + ") "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumStockSpendForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Phase 8 — count of paid stock orders in a period (for summary stats). */
    @org.springframework.data.jpa.repository.Query(
            "select count(o) from StockOrder o "
            + "where o.buyerBusiness.id = :businessId "
            + "and o.status not in ("
            + "  com.momocircle.domain.enums.StockOrderStatus.PENDING_PAYMENT,"
            + "  com.momocircle.domain.enums.StockOrderStatus.PAYMENT_PROCESSING,"
            + "  com.momocircle.domain.enums.StockOrderStatus.CANCELLED"
            + ") "
            + "and o.createdAt >= :from and o.createdAt < :to")
    long countPaidStockOrdersForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);
    /** Delivery fees inside paid-and-beyond stock orders, so cash flow can show them as delivery, not stock. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(o.deliveryFeeSnapshot), 0) from StockOrder o "
            + "where o.buyerBusiness.id = :businessId "
            + "and o.status not in ("
            + "  com.momocircle.domain.enums.StockOrderStatus.PENDING_PAYMENT,"
            + "  com.momocircle.domain.enums.StockOrderStatus.PAYMENT_PROCESSING,"
            + "  com.momocircle.domain.enums.StockOrderStatus.CANCELLED"
            + ") "
            + "and o.createdAt >= :from and o.createdAt < :to")
    java.math.BigDecimal sumStockDeliveryFeesForBusiness(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Newest sales whose customer payment was provider-confirmed: the population reconciliation checks. */
    @org.springframework.data.jpa.repository.Query("select o from StockOrder o where o.payment is not null and o.payment.status = "
            + "com.momocircle.domain.enums.PaymentStatus.SUCCESSFUL order by o.createdAt desc")
    java.util.List<StockOrder> findRecentPaid(org.springframework.data.domain.Pageable pageable);
}
