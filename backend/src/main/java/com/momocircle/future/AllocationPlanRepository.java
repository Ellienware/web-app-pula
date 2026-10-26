package com.momocircle.future;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AllocationPlanRepository extends JpaRepository<AllocationPlan, String> {
    Page<AllocationPlan> findByOwnerUserIdAndStatusNotOrderByCreatedAtDesc(String ownerUserId, PlanStatus status, Pageable p);
    Optional<AllocationPlan> findByIdAndOwnerUserId(String id, String ownerUserId);
}
