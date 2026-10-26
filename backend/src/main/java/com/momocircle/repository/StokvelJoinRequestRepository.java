package com.momocircle.repository;

import com.momocircle.domain.StokvelJoinRequest;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.JoinRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every transition out of PENDING is ONE guarded UPDATE whose WHERE clause states the legal source state, so two racing
 * decisions (or a decision racing a cancel) cannot both win. {@code pending_key} is cleared in the same statement.
 */
public interface StokvelJoinRequestRepository extends JpaRepository<StokvelJoinRequest, String> {

    Optional<StokvelJoinRequest> findByCircleIdAndUserIdAndStatus(String circleId, String userId, JoinRequestStatus status);

    @Query("select r from StokvelJoinRequest r where r.circle.id = :circleId order by r.createdAt desc")
    List<StokvelJoinRequest> findAllForCircle(@Param("circleId") String circleId);

    @Query("select r from StokvelJoinRequest r where r.circle.id = :circleId and r.status = :status order by r.createdAt desc")
    List<StokvelJoinRequest> findForCircleByStatus(@Param("circleId") String circleId, @Param("status") JoinRequestStatus status);

    @Query("select r from StokvelJoinRequest r where r.user.id = :userId order by r.createdAt desc")
    List<StokvelJoinRequest> findForUser(@Param("userId") String userId);

    /** Most recent decided request of a person for a stokvel (used for the re-request cooldown after a rejection). */
    @Query("select r from StokvelJoinRequest r where r.circle.id = :circleId and r.user.id = :userId and r.status = :status "
            + "order by r.decidedAt desc")
    List<StokvelJoinRequest> findDecided(@Param("circleId") String circleId, @Param("userId") String userId,
                                         @Param("status") JoinRequestStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update StokvelJoinRequest r set r.status = :to, r.pendingKey = null, r.decidedBy = :by, r.decidedAt = :now, "
            + "r.decisionNote = :note, r.updatedAt = :now where r.id = :id and r.circle.id = :circleId and r.status = :pending")
    int decide(@Param("id") String id, @Param("circleId") String circleId, @Param("to") JoinRequestStatus to,
               @Param("by") User by, @Param("now") Instant now, @Param("note") String note,
               @Param("pending") JoinRequestStatus pending);

    /** Requester withdraws their own request. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update StokvelJoinRequest r set r.status = :cancelled, r.pendingKey = null, r.decidedBy = r.user, r.decidedAt = :now, "
            + "r.updatedAt = :now where r.id = :id and r.user.id = :userId and r.status = :pending")
    int cancelOwn(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now,
                  @Param("pending") JoinRequestStatus pending, @Param("cancelled") JoinRequestStatus cancelled);
}
