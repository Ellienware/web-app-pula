package com.momocircle.repository;

import com.momocircle.domain.BranchStaff;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchStaffRepository extends JpaRepository<BranchStaff, String> {
    List<BranchStaff> findByBranchIdOrderByCreatedAtAsc(String branchId);
    Optional<BranchStaff> findByBranchIdAndUserId(String branchId, String userId);
    List<BranchStaff> findByUserId(String userId);
}
