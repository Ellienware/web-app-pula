package com.momocircle.repository;

import com.momocircle.domain.BulkGroupEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BulkGroupEventRepository extends JpaRepository<BulkGroupEvent, String> {
    Page<BulkGroupEvent> findByGroupIdOrderByCreatedAtDesc(String groupId, Pageable pageable);

    /** Group-level events plus events about one business only: what a participant is allowed to see. */
    @Query("select e from BulkGroupEvent e where e.groupId = :groupId and (e.businessId is null or e.businessId = :businessId) "
            + "order by e.createdAt desc")
    Page<BulkGroupEvent> findVisibleToBusiness(@Param("groupId") String groupId, @Param("businessId") String businessId, Pageable pageable);
}
