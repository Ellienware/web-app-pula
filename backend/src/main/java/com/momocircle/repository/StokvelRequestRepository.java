package com.momocircle.repository;

import com.momocircle.domain.StokvelRequest;
import com.momocircle.domain.enums.StokvelRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StokvelRequestRepository extends JpaRepository<StokvelRequest, String> {

    List<StokvelRequest> findByCircleIdOrderByCreatedAtDesc(String circleId);

    Optional<StokvelRequest> findByIdAndCircleId(String id, String circleId);

    Optional<StokvelRequest> findByCircleIdAndRequesterIdAndIdempotencyKey(String circleId, String requesterId, String key);

    /** Row lock: serialises votes, cancel, expiry and order linking on one request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StokvelRequest r where r.id = :id and r.circle.id = :circleId")
    Optional<StokvelRequest> findForUpdate(@Param("id") String id, @Param("circleId") String circleId);

    boolean existsByCircleIdAndRequesterIdAndRequestTypeAndAmountAndPurposeAndStatus(
            String circleId, String requesterId, com.momocircle.domain.enums.StokvelRequestType type,
            BigDecimal amount, String purpose, StokvelRequestStatus status);

    @Query("select coalesce(sum(r.amount), 0) from StokvelRequest r where r.circle.id = :circleId and r.status in :statuses")
    BigDecimal sumAmountByStatuses(@Param("circleId") String circleId,
                                   @Param("statuses") Collection<StokvelRequestStatus> statuses);

    @Query("select r.id from StokvelRequest r where r.status in :statuses and r.expiresAt < :now")
    List<String> findIdsPastExpiry(@Param("statuses") Collection<StokvelRequestStatus> statuses, @Param("now") Instant now);
}
