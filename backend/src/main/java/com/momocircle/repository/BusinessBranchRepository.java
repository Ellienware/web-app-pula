package com.momocircle.repository;

import com.momocircle.domain.BusinessBranch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessBranchRepository extends JpaRepository<BusinessBranch, String> {
    List<BusinessBranch> findByBusinessIdOrderByCreatedAtAsc(String businessId);

    boolean existsByBusinessIdAndBranchCode(String businessId, String branchCode);

    boolean existsByBusinessIdAndBranchCodeAndIdNot(String businessId, String branchCode, String id);
}
