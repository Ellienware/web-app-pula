package com.momocircle.repository;

import com.momocircle.domain.ScamAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScamAssessmentRepository extends JpaRepository<ScamAssessment, String> {
    List<ScamAssessment> findByUserIdOrderByCreatedAtDesc(String userId);
}
