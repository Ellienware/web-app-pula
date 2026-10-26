package com.momocircle.repository;

import com.momocircle.domain.BranchItemAvailability;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchItemAvailabilityRepository extends JpaRepository<BranchItemAvailability, String> {
    Optional<BranchItemAvailability> findByBranchIdAndProductId(String branchId, String productId);
    List<BranchItemAvailability> findByBranchId(String branchId);
}
