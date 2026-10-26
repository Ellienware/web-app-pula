package com.momocircle.repository;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, String> {
    List<ServiceRequest> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<ServiceRequest> findByBusinessIdAndStatusNotOrderByCreatedAtDesc(String businessId, ServiceRequestStatus status);
    Optional<ServiceRequest> findByCustomerIdAndIdempotencyKey(String customerId, String key);

    /** Row lock: serialises quoting, accepting, cancelling and material edits on one request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id")
    Optional<ServiceRequest> findForUpdate(@Param("id") String id);

    @Query("select r.id from ServiceRequest r where r.status in :statuses and r.expiresAt < :now")
    List<String> findIdsPastExpiry(@Param("statuses") Collection<ServiceRequestStatus> statuses, @Param("now") Instant now);

    long countByCustomerIdAndCreatedAtAfter(String customerId, Instant after);
}
