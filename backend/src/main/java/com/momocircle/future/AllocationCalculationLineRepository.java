package com.momocircle.future;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AllocationCalculationLineRepository extends JpaRepository<AllocationCalculationLine, String> {
    List<AllocationCalculationLine> findByCalculationIdOrderByPositionAsc(String calculationId);
}
