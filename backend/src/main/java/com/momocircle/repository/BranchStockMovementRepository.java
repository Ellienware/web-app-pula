package com.momocircle.repository;

import com.momocircle.branch.BranchMovementType;
import com.momocircle.domain.BranchStockMovement;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchStockMovementRepository extends JpaRepository<BranchStockMovement, String> {

    boolean existsByOrderIdAndProductIdAndMovementType(String orderId, String productId, BranchMovementType type);

    List<BranchStockMovement> findByOrderIdAndMovementType(String orderId, BranchMovementType type);

    /** Movements touching one branch (either side), newest first. */
    @Query("select m from BranchStockMovement m where m.fromBranchId = :branchId or m.toBranchId = :branchId order by m.createdAt desc")
    List<BranchStockMovement> findForBranch(@Param("branchId") String branchId, Pageable pageable);
}
