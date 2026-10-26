package com.momocircle.risk;

import com.momocircle.domain.ScamAssessment;
import com.momocircle.domain.enums.ScamRiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/**
 * Read-only boundary onto ScamShield's own table. ScamShield keeps its responsibility (helping users recognise scams in text
 * they submit); this layer only counts that user's recent HIGH results as one signal and never duplicates ScamShield's logic.
 */
public interface RiskScamSignalRepository extends JpaRepository<ScamAssessment, String> {
    long countByUserIdAndRiskLevelAndCreatedAtAfter(String userId, ScamRiskLevel level, Instant after);
}
