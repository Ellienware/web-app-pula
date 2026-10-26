package com.momocircle.repository;

import com.momocircle.domain.SaveToBuyRequest;
import com.momocircle.domain.enums.RelatedOrderKind;
import com.momocircle.domain.enums.SaveToBuyScope;
import com.momocircle.domain.enums.SaveToBuyStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SaveToBuyRequestRepository extends JpaRepository<SaveToBuyRequest, String> {

    List<SaveToBuyRequest> findByOwnerIdAndScopeOrderByCreatedAtDesc(String ownerId, SaveToBuyScope scope);

    List<SaveToBuyRequest> findByCircleIdOrderByCreatedAtDesc(String circleId);

    Optional<SaveToBuyRequest> findByOwnerIdAndIdempotencyKey(String ownerId, String key);

    Optional<SaveToBuyRequest> findByOrderKindAndOrderId(RelatedOrderKind kind, String orderId);

    Optional<SaveToBuyRequest> findByStokvelRequestId(String stokvelRequestId);

    boolean existsByStokvelRequestId(String stokvelRequestId);

    /** Row lock: serialises checkout attachment, payment resolution, cancel and confirm on one request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SaveToBuyRequest r where r.id = :id")
    Optional<SaveToBuyRequest> findForUpdate(@Param("id") String id);

    @Query("""
        select count(r) > 0 from SaveToBuyRequest r
        where r.goal.id = :goalId and r.owner.id = :ownerId and r.vertical = :vertical
          and r.requestedAmount = :amount and r.purpose = :purpose and r.status in :statuses
    """)
    boolean existsOpenDuplicate(@Param("goalId") String goalId, @Param("ownerId") String ownerId,
                                @Param("vertical") com.momocircle.domain.enums.SaveToBuyVertical vertical,
                                @Param("amount") BigDecimal amount, @Param("purpose") String purpose,
                                @Param("statuses") Collection<SaveToBuyStatus> statuses);
}
