package com.momocircle.repository;

import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.enums.RefundStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, String> {

    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRefund> findByProviderRefundReference(String providerRefundReference);

    List<PaymentRefund> findByOrderIdOrderByCreatedAtAsc(String orderId);

    List<PaymentRefund> findByPaymentIdOrderByCreatedAtAsc(String paymentId);

    /** Completed refunds of a payment that returned value through their OWN compensating ledger transfer. */
    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r where r.payment.id = :paymentId "
            + "and r.status = com.momocircle.domain.enums.RefundStatus.REFUNDED and r.ledgerTransactionId is not null")
    BigDecimal sumCompensatedByTransferForPayment(@Param("paymentId") String paymentId);

    /** Refunds of a payment that are not failed: money already returned or still on its way. */
    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r where r.payment.id = :paymentId "
            + "and r.status <> com.momocircle.domain.enums.RefundStatus.FAILED")
    BigDecimal sumNotFailedByPayment(@Param("paymentId") String paymentId);

    /** Completed refunds only. */
    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r where r.order.id = :orderId "
            + "and r.status = com.momocircle.domain.enums.RefundStatus.REFUNDED")
    BigDecimal sumRefundedByOrder(@Param("orderId") String orderId);

    /** Refunds still in flight that have not been touched since the cutoff: candidates for the retry / status-poll pass. */
    @Query("select r from PaymentRefund r where r.status in (com.momocircle.domain.enums.RefundStatus.PENDING, "
            + "com.momocircle.domain.enums.RefundStatus.PROCESSING) and r.updatedAt < :before order by r.createdAt asc")
    List<PaymentRefund> findInFlightUntouchedSince(@Param("before") Instant before);

    /** Webhook matching when the provider reference is not yet stored (webhook beat the create response). */
    @Query("select r from PaymentRefund r where r.originalReference = :reference and r.status in "
            + "(com.momocircle.domain.enums.RefundStatus.PENDING, com.momocircle.domain.enums.RefundStatus.PROCESSING, "
            + "com.momocircle.domain.enums.RefundStatus.NEEDS_ATTENTION) order by r.createdAt asc")
    List<PaymentRefund> findOpenByOriginalReference(@Param("reference") String reference);

    List<PaymentRefund> findByStatusInOrderByCreatedAtDesc(Collection<RefundStatus> statuses, Pageable pageable);

    List<PaymentRefund> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Cash-flow info line: completed refunds on a business's food orders, by order creation period. */
    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r where r.order.business.id = :businessId "
            + "and r.order.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and r.status = com.momocircle.domain.enums.RefundStatus.REFUNDED "
            + "and r.order.createdAt >= :from and r.order.createdAt < :to")
    BigDecimal sumFoodRefundsForBusiness(@Param("businessId") String businessId,
                                         @Param("from") Instant from, @Param("to") Instant to);

    /** COMPLETED refunds against revenue-status orders, per branch (null id = no branch). Pending/failed refunds never count. */
    @Query("select b.id, coalesce(sum(r.amount), 0) from PaymentRefund r join r.order o left join o.branch b "
            + "where o.business.id = :businessId and r.status = com.momocircle.domain.enums.RefundStatus.REFUNDED "
            + "and o.status in :statuses and o.createdAt >= :from and o.createdAt < :to group by b.id")
    java.util.List<Object[]> refundedByBranch(@Param("businessId") String businessId,
                                              @Param("statuses") java.util.Collection<com.momocircle.domain.enums.OrderStatus> statuses,
                                              @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
