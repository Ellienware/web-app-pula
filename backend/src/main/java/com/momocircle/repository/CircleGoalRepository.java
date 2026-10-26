package com.momocircle.repository;

import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.enums.GoalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CircleGoalRepository extends JpaRepository<CircleGoal, String> {
    List<CircleGoal> findByCircleIdOrderByCreatedAtDesc(String circleId);

    /** Used by AffordabilityService to gently note active goals across every circle a user belongs to. */
    List<CircleGoal> findByCircleIdInAndStatus(List<String> circleIds, GoalStatus status);

    /** Save-to-Buy: goals designated for a purchase, across the circles a user belongs to. */
    List<CircleGoal> findByCircleIdInAndPurchaseVerticalIsNotNullOrderByCreatedAtDesc(java.util.Collection<String> circleIds);

    List<CircleGoal> findByCircleIdInOrderByCreatedAtDesc(java.util.Collection<String> circleIds);
}
