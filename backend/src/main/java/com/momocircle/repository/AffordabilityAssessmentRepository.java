package com.momocircle.repository;

import com.momocircle.domain.AffordabilityAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AffordabilityAssessmentRepository extends JpaRepository<AffordabilityAssessment, String> {
    List<AffordabilityAssessment> findByUserIdOrderByCreatedAtDesc(String userId);
}
