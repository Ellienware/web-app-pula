package com.momocircle.repository;

import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.domain.enums.SettlementType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RestaurantSettlementRepository extends JpaRepository<RestaurantSettlement, String> {

    Optional<RestaurantSettlement> findByOrderId(String orderId);

    List<RestaurantSettlement> findByPaymentId(String paymentId);

    List<RestaurantSettlement> findByStatusInOrderByCreatedAtDesc(Collection<SettlementStatus> statuses, Pageable pageable);

    List<RestaurantSettlement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<RestaurantSettlement> findByTypeOrderByCreatedAtDesc(SettlementType type, Pageable pageable);

    /** Settlements whose dispute window has passed and that are waiting for a payout. */
    @Query("select s from RestaurantSettlement s where s.status = com.momocircle.domain.enums.SettlementStatus.ELIGIBLE "
            + "and s.eligibleAt <= :now order by s.eligibleAt asc")
    List<RestaurantSettlement> findPayable(@Param("now") Instant now);

    /** FAILED settlements still under the attempt ceiling, for the safe retry pass. */
    @Query("select s from RestaurantSettlement s where s.status = com.momocircle.domain.enums.SettlementStatus.FAILED "
            + "and s.payoutAttempts < :maxAttempts order by s.updatedAt asc")
    List<RestaurantSettlement> findRetryable(@Param("maxAttempts") int maxAttempts);

    /** PROCESSING for too long: the provider call is idempotent, so it is safe to re-drive. */
    @Query("select s from RestaurantSettlement s where s.status = com.momocircle.domain.enums.SettlementStatus.PROCESSING "
            + "and s.updatedAt < :before order by s.updatedAt asc")
    List<RestaurantSettlement> findStuckProcessing(@Param("before") Instant before);

    /**
     * Atomic claim: only the single caller whose UPDATE matches moves ELIGIBLE/FAILED to PROCESSING, so two
     * scheduler instances (or a scheduler and an admin retry) cannot both send the payout.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RestaurantSettlement s set s.status = com.momocircle.domain.enums.SettlementStatus.PROCESSING, "
            + "s.payoutAttempts = s.payoutAttempts + 1, s.updatedAt = :now "
            + "where s.id = :id and s.status in (com.momocircle.domain.enums.SettlementStatus.ELIGIBLE, "
            + "com.momocircle.domain.enums.SettlementStatus.FAILED)")
    int claimForPayout(@Param("id") String id, @Param("now") Instant now);

    /** Atomic claim of a settlement stuck in PROCESSING (same idempotent provider call is repeated). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RestaurantSettlement s set s.payoutAttempts = s.payoutAttempts + 1, s.updatedAt = :now "
            + "where s.id = :id and s.status = com.momocircle.domain.enums.SettlementStatus.PROCESSING and s.updatedAt < :before")
    int claimStuck(@Param("id") String id, @Param("now") Instant now, @Param("before") Instant before);

    // ---- Cash flow (period = the ORDER's creation date, same basis as food sales) ----

    @Query("select coalesce(sum(s.commissionAmount), 0) from RestaurantSettlement s where s.business.id = :businessId and s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status not in (com.momocircle.domain.enums.SettlementStatus.NOT_ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.CANCELLED) "
            + "and s.order.createdAt >= :from and s.order.createdAt < :to")
    BigDecimal sumSaleCommissionForBusiness(@Param("businessId") String businessId,
                                            @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId and s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER "
            + "and s.type = com.momocircle.domain.enums.SettlementType.MERCHANT_PROTECTION "
            + "and s.status not in (com.momocircle.domain.enums.SettlementStatus.NOT_ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.CANCELLED) "
            + "and s.order.createdAt >= :from and s.order.createdAt < :to")
    BigDecimal sumCompensationForBusiness(@Param("businessId") String businessId,
                                          @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId and s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER "
            + "and s.status = com.momocircle.domain.enums.SettlementStatus.PAID "
            + "and s.order.createdAt >= :from and s.order.createdAt < :to")
    BigDecimal sumPaidForBusiness(@Param("businessId") String businessId,
                                  @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId and s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER "
            + "and s.status in (com.momocircle.domain.enums.SettlementStatus.ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.PROCESSING, "
            + "com.momocircle.domain.enums.SettlementStatus.FAILED, com.momocircle.domain.enums.SettlementStatus.ON_HOLD) "
            + "and s.order.createdAt >= :from and s.order.createdAt < :to")
    BigDecimal sumOwedForBusiness(@Param("businessId") String businessId,
                                  @Param("from") Instant from, @Param("to") Instant to);

    // ---- PULA-side (admin) totals ----

    @Query("select coalesce(sum(s.pulaFulfilmentLoss), 0) from RestaurantSettlement s where s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER and s.type = "
            + "com.momocircle.domain.enums.SettlementType.MERCHANT_PROTECTION and s.status <> com.momocircle.domain.enums.SettlementStatus.CANCELLED")
    BigDecimal sumPulaFulfilmentLoss();

    @Query("select coalesce(sum(s.commissionAmount), 0) from RestaurantSettlement s where s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER and s.status in "
            + "(com.momocircle.domain.enums.SettlementStatus.ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.PROCESSING, "
            + "com.momocircle.domain.enums.SettlementStatus.PAID, com.momocircle.domain.enums.SettlementStatus.FAILED)")
    BigDecimal sumCommissionEarned();

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER and s.status = :status")
    BigDecimal sumNetByStatus(@Param("status") SettlementStatus status);

    @Query("select count(s) from RestaurantSettlement s where "+"s.sourceType = com.momocircle.domain.enums.SettlementSourceType.FOOD_ORDER and s.status = :status")
    long countFoodByStatus(@Param("status") SettlementStatus status);

    long countByStatus(SettlementStatus status);

    // ---- Marketplace (all sources) ----

    Optional<RestaurantSettlement> findBySourceTypeAndSourceId(
            com.momocircle.domain.enums.SettlementSourceType type, String sourceId);

    List<RestaurantSettlement> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    List<RestaurantSettlement> findBySourceTypeOrderByCreatedAtDesc(
            com.momocircle.domain.enums.SettlementSourceType type, Pageable pageable);

    List<RestaurantSettlement> findBySourceTypeAndStatusInOrderByCreatedAtDesc(
            com.momocircle.domain.enums.SettlementSourceType type, Collection<SettlementStatus> statuses, Pageable pageable);

    /** Rows of [sourceType, count, gross, commission, paymentFee, adjustments, net, refund] across all non-cancelled settlements. */
    @Query("select s.sourceType, count(s), coalesce(sum(s.grossAmount), 0), coalesce(sum(s.commissionAmount), 0), "
            + "coalesce(sum(s.paymentFeeAmount), 0), coalesce(sum(s.adjustmentAmount), 0), coalesce(sum(s.netAmount), 0), "
            + "coalesce(sum(s.refundAmount), 0) from RestaurantSettlement s "
            + "where s.status <> com.momocircle.domain.enums.SettlementStatus.CANCELLED group by s.sourceType")
    List<Object[]> totalsBySource();

    /** Rows of [status, count, net] across every source. */
    @Query("select s.status, count(s), coalesce(sum(s.netAmount), 0) from RestaurantSettlement s group by s.status")
    List<Object[]> totalsByStatus();

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId and s.status = :status")
    BigDecimal sumNetForBusinessAndStatus(@Param("businessId") String businessId, @Param("status") SettlementStatus status);

    @Query("select s.sourceType, count(s), coalesce(sum(s.grossAmount), 0), coalesce(sum(s.commissionAmount), 0), "
            + "coalesce(sum(s.paymentFeeAmount), 0), coalesce(sum(s.refundAmount), 0), coalesce(sum(s.netAmount), 0) "
            + "from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.status <> com.momocircle.domain.enums.SettlementStatus.CANCELLED "
            + "and s.createdAt >= :from and s.createdAt < :to group by s.sourceType")
    List<Object[]> totalsForBusiness(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.pulaFulfilmentLoss), 0) from RestaurantSettlement s where s.type = "
            + "com.momocircle.domain.enums.SettlementType.MERCHANT_PROTECTION and s.status <> com.momocircle.domain.enums.SettlementStatus.CANCELLED")
    BigDecimal sumPulaLossAllSources();

    /** Row lock so an adjustment and a payout claim on the same settlement cannot interleave. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RestaurantSettlement s where s.id = :id")
    Optional<RestaurantSettlement> findByIdForUpdate(@Param("id") String id);

    // ---- PULA Future: merchant income basis (READ-ONLY; SALE settlements only, all marketplace source types) ----
    // Paid = paid out by the platform's payout workflow, which is simulated in this deployment.

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status = com.momocircle.domain.enums.SettlementStatus.PAID and s.paidAt >= :from and s.paidAt < :to")
    BigDecimal futurePaidNet(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.grossAmount), 0) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status = com.momocircle.domain.enums.SettlementStatus.PAID and s.paidAt >= :from and s.paidAt < :to")
    BigDecimal futurePaidGross(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.refundAmount), 0) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status = com.momocircle.domain.enums.SettlementStatus.PAID and s.paidAt >= :from and s.paidAt < :to")
    BigDecimal futurePaidRefunded(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(s) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status = com.momocircle.domain.enums.SettlementStatus.PAID and s.paidAt >= :from and s.paidAt < :to")
    long futurePaidCount(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status in (com.momocircle.domain.enums.SettlementStatus.ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.PROCESSING, "
            + "com.momocircle.domain.enums.SettlementStatus.ON_HOLD, com.momocircle.domain.enums.SettlementStatus.FAILED) "
            + "and s.createdAt >= :from and s.createdAt < :to")
    BigDecimal futurePendingNet(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(s) from RestaurantSettlement s where s.business.id = :businessId "
            + "and s.type = com.momocircle.domain.enums.SettlementType.SALE "
            + "and s.status in (com.momocircle.domain.enums.SettlementStatus.ELIGIBLE, com.momocircle.domain.enums.SettlementStatus.PROCESSING, "
            + "com.momocircle.domain.enums.SettlementStatus.ON_HOLD, com.momocircle.domain.enums.SettlementStatus.FAILED) "
            + "and s.createdAt >= :from and s.createdAt < :to")
    long futurePendingCount(@Param("businessId") String businessId, @Param("from") Instant from, @Param("to") Instant to);
}
