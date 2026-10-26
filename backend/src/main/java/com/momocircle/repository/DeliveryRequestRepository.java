package com.momocircle.repository;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryRequestRepository extends JpaRepository<DeliveryRequest, String> {
    Optional<DeliveryRequest> findByOrderId(String orderId);
    Optional<DeliveryRequest> findByStockOrderId(String stockOrderId);

    /**
     * Open jobs a specific driver may see: independent jobs (no pool) plus jobs
     * reserved for a pool the driver is an active member of. Pool jobs are
     * invisible to everyone else.
     */
    @Query("select d from DeliveryRequest d where d.status = :status and (d.poolBusiness is null "
            + "or d.poolBusiness.id in (select m.business.id from DeliveryPoolMember m "
            + "where m.driver.id = :driverId and m.active = true)) order by d.createdAt asc")
    Page<DeliveryRequest> findOpenEligibleForDriver(@Param("status") DeliveryStatus status,
                                                    @Param("driverId") String driverId, Pageable pageable);
    Page<DeliveryRequest> findByStatusOrderByCreatedAtAsc(DeliveryStatus status, Pageable pageable);
    Page<DeliveryRequest> findByDriverIdOrderByCreatedAtDesc(String driverId, Pageable pageable);

    /** Used by SellerDashboardService — explicit join rather than a three-level derived-name query. */
    @Query("select d from DeliveryRequest d where d.order.business.id = :businessId")
    List<DeliveryRequest> findByOrderBusinessId(@Param("businessId") String businessId);

    /**
     * PULA Food: food deliveries whose current driver search began before the cutoff, that still have no
     * driver and haven't been flagged to the customer. Uses driverSearchStartedAt, never updatedAt.
     * Rows from before V32 have a backfilled value, so none are missed.
     */
    @Query("select d from DeliveryRequest d where d.status = com.momocircle.domain.enums.DeliveryStatus.REQUESTED "
            + "and d.order is not null and d.order.kind = com.momocircle.domain.enums.OrderKind.FOOD "
            + "and d.noDriverNotifiedAt is null and d.driverSearchStartedAt < :cutoff")
    List<DeliveryRequest> findUnclaimedFoodDeliveriesCreatedBefore(@Param("cutoff") java.time.Instant cutoff);
}
