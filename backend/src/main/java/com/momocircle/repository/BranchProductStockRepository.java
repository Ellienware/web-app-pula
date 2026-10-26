package com.momocircle.repository;

import com.momocircle.domain.BranchProductStock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchProductStockRepository extends JpaRepository<BranchProductStock, String> {

    List<BranchProductStock> findByBranchIdOrderByCreatedAtAsc(String branchId);

    Optional<BranchProductStock> findByBranchIdAndProductId(String branchId, String productId);

    /** Row lock for every quantity change, so two checkouts or transfers cannot both spend the same units. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BranchProductStock s where s.branch.id = :branchId and s.product.id = :productId")
    Optional<BranchProductStock> findForUpdate(@Param("branchId") String branchId, @Param("productId") String productId);

    @Query("select coalesce(sum(s.quantity), 0) from BranchProductStock s where s.product.id = :productId")
    long totalAllocated(@Param("productId") String productId);
}
