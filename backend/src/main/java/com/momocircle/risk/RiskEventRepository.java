package com.momocircle.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskEventRepository extends JpaRepository<RiskEvent, String> {
    List<RiskEvent> findByAssessmentIdOrderByCreatedAtAsc(String assessmentId);
}
