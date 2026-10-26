package com.momocircle.repository;

import com.momocircle.domain.BulkPurchaseGroup;
import com.momocircle.domain.enums.BulkGroupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BulkPurchaseGroupRepository extends JpaRepository<BulkPurchaseGroup, String> {

    /** Serialises every mutation of one group so concurrent joins/confirmations cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from BulkPurchaseGroup g where g.id = :id")
    Optional<BulkPurchaseGroup> findForUpdate(@Param("id") String id);

    Page<BulkPurchaseGroup> findByStatusInOrderByDeadlineAsc(Collection<BulkGroupStatus> statuses, Pageable pageable);

    Page<BulkPurchaseGroup> findBySupplierBusinessIdAndStatusNotOrderByCreatedAtDesc(
            String supplierBusinessId, BulkGroupStatus excluded, Pageable pageable);

    Page<BulkPurchaseGroup> findByCreatorBusinessIdOrderByCreatedAtDesc(String creatorBusinessId, Pageable pageable);

    List<BulkPurchaseGroup> findByStatusInAndDeadlineBefore(Collection<BulkGroupStatus> statuses, Instant before);

    List<BulkPurchaseGroup> findByStatusIn(Collection<BulkGroupStatus> statuses);

    /** Groups the business created or participates in (any non-withdrawn allocation). */
    @Query("select distinct g from BulkPurchaseGroup g where g.creatorBusiness.id = :businessId or g.id in "
            + "(select a.group.id from BulkAllocation a where a.business.id = :businessId "
            + "and a.status <> com.momocircle.domain.enums.BulkAllocationStatus.WITHDRAWN) "
            + "order by g.createdAt desc")
    Page<BulkPurchaseGroup> findInvolving(@Param("businessId") String businessId, Pageable pageable);
}
