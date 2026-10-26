package com.momocircle.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, String> {
    Optional<RiskAssessment> findFirstByIntentIdOrderByCreatedAtDesc(String intentId);
    long countByUserIdAndTypeAndCreatedAtAfter(String userId, RiskType type, Instant after);

    @Query("select a from RiskAssessment a where (:type is null or a.type = :type) and (:decision is null or a.decision = :decision) "
            + "and (:review is null or a.reviewStatus = :review) and (:userId is null or a.userId = :userId) order by a.createdAt desc")
    Page<RiskAssessment> search(@Param("type") RiskType type, @Param("decision") RiskDecision decision,
                                @Param("review") ReviewStatus review, @Param("userId") String userId, Pageable pageable);
}
