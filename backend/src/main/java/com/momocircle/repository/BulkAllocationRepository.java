package com.momocircle.repository;

import com.momocircle.domain.BulkAllocation;
import com.momocircle.domain.enums.BulkAllocationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BulkAllocationRepository extends JpaRepository<BulkAllocation, String> {
    List<BulkAllocation> findByGroupIdOrderByCreatedAtAsc(String groupId);
    Optional<BulkAllocation> findByGroupIdAndBusinessId(String groupId, String businessId);
    Page<BulkAllocation> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);
    Optional<BulkAllocation> findByStockOrderId(String stockOrderId);
    List<BulkAllocation> findByStatusIn(Collection<BulkAllocationStatus> statuses);

    @Query("select coalesce(sum(a.requestedPacks), 0) from BulkAllocation a where a.group.id = :groupId "
            + "and a.status in (com.momocircle.domain.enums.BulkAllocationStatus.JOINED, "
            + "com.momocircle.domain.enums.BulkAllocationStatus.CONFIRMED, "
            + "com.momocircle.domain.enums.BulkAllocationStatus.PENDING_RECONFIRMATION)")
    long sumActiveRequestedPacks(@Param("groupId") String groupId);

    @Query("select a from BulkAllocation a where a.refundDueAmount > 0 order by a.updatedAt desc")
    Page<BulkAllocation> findWithRefundDue(Pageable pageable);
}
