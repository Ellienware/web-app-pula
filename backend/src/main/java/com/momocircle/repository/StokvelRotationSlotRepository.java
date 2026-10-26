package com.momocircle.repository;

import com.momocircle.domain.StokvelRotationSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StokvelRotationSlotRepository extends JpaRepository<StokvelRotationSlot, String> {
    List<StokvelRotationSlot> findByCircleIdOrderByPositionAsc(String circleId);
    boolean existsByCircleIdAndUserId(String circleId, String userId);
}
