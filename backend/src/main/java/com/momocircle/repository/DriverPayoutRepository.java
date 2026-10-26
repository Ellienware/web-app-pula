package com.momocircle.repository;

import com.momocircle.domain.DriverPayout;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, String> {
    Optional<DriverPayout> findByIdempotencyKey(String idempotencyKey);
    Page<DriverPayout> findByStatusOrderByUpdatedAtDesc(com.momocircle.domain.enums.PayoutStatus status, Pageable pageable);
    List<DriverPayout> findByStatusAndAttemptsLessThanAndUpdatedAtBefore(com.momocircle.domain.enums.PayoutStatus status, int attempts, Instant before);
    Page<DriverPayout> findByDriverIdOrderByCreatedAtDesc(String driverId, Pageable pageable);

    /** Rows of [status, count, amount]: driver delivery earnings, reused (not duplicated) by marketplace reporting. */
    @org.springframework.data.jpa.repository.Query("select d.status, count(d), coalesce(sum(d.amount), 0) from DriverPayout d group by d.status")
    List<Object[]> totalsByStatus();
}
