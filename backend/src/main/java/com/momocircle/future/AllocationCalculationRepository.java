package com.momocircle.future;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AllocationCalculationRepository extends JpaRepository<AllocationCalculation, String> {
    Optional<AllocationCalculation> findByPlanIdAndIdempotencyKey(String planId, String key);
    Page<AllocationCalculation> findByPlanIdOrderByCreatedAtDesc(String planId, Pageable p);
}
