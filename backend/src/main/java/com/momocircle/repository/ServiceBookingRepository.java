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

public interface ServiceBookingRepository extends JpaRepository<ServiceBooking, String> {
    Optional<ServiceBooking> findByRequestId(String requestId);
    Optional<ServiceBooking> findByPaymentId(String paymentId);
    List<ServiceBooking> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<ServiceBooking> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ServiceBooking b where b.id = :id")
    Optional<ServiceBooking> findForUpdate(@Param("id") String id);

    Page<ServiceBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Newest sales whose customer payment was provider-confirmed: the population reconciliation checks. */
    @org.springframework.data.jpa.repository.Query("select o from ServiceBooking o where o.payment is not null and o.payment.status = "
            + "com.momocircle.domain.enums.PaymentStatus.SUCCESSFUL order by o.createdAt desc")
    java.util.List<ServiceBooking> findRecentPaid(org.springframework.data.domain.Pageable pageable);
}
