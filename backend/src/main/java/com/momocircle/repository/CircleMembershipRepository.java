package com.momocircle.repository;

import com.momocircle.domain.CircleMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CircleMembershipRepository extends JpaRepository<CircleMembership, String> {

    List<CircleMembership> findByCircleId(String circleId);

    List<CircleMembership> findByUserId(String userId);

    Optional<CircleMembership> findByCircleIdAndUserId(String circleId, String userId);

    boolean existsByCircleIdAndUserId(String circleId, String userId);

    long countByCircleId(String circleId);
}
