package com.momocircle.repository;

import com.momocircle.domain.DeliveryEarningsAllocation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryEarningsAllocationRepository extends JpaRepository<DeliveryEarningsAllocation, String> {

    Optional<DeliveryEarningsAllocation> findByDeliveryRequestId(String deliveryRequestId);

    /** Driver earnings from PULA Ride food deliveries on this business's orders created in the period. */
    @Query("select coalesce(sum(a.driverShare), 0) from DeliveryEarningsAllocation a "
            + "where a.deliveryRequest.order.business.id = :businessId "
            + "and a.deliveryRequest.order.createdAt >= :from and a.deliveryRequest.order.createdAt < :to")
    BigDecimal sumDriverShareForBusiness(@Param("businessId") String businessId,
                                         @Param("from") Instant from, @Param("to") Instant to);

    /** PULA's delivery-fee income from the same deliveries. */
    @Query("select coalesce(sum(a.pulaShare), 0) from DeliveryEarningsAllocation a "
            + "where a.deliveryRequest.order.business.id = :businessId "
            + "and a.deliveryRequest.order.createdAt >= :from and a.deliveryRequest.order.createdAt < :to")
    BigDecimal sumPulaShareForBusiness(@Param("businessId") String businessId,
                                       @Param("from") Instant from, @Param("to") Instant to);

}
