package com.momocircle.repository;

import com.momocircle.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByProviderReference(String providerReference);

    /** Row-locked load for confirmation, so a pay call, the sweep and a refresh cannot all fulfil the same order. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Payment p where p.providerReference = :ref")
    Optional<Payment> findByProviderReferenceForUpdate(@org.springframework.data.repository.query.Param("ref") String ref);
    java.util.List<Payment> findByStatusAndFailureReasonStartingWith(com.momocircle.domain.enums.PaymentStatus status, String prefix);
    long countByStatus(com.momocircle.domain.enums.PaymentStatus status);
    org.springframework.data.domain.Page<Payment> findByProvider(String provider, org.springframework.data.domain.Pageable pageable);
    java.util.List<Payment> findByStatus(com.momocircle.domain.enums.PaymentStatus status);
}
