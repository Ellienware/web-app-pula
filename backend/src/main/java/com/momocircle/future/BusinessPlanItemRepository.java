package com.momocircle.future;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BusinessPlanItemRepository extends JpaRepository<BusinessPlanItem, String> {
    List<BusinessPlanItem> findByPlanIdOrderByCreatedAtAsc(String planId);
}
