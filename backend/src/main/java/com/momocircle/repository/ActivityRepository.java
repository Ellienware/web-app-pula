package com.momocircle.repository;

import com.momocircle.domain.ActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<ActivityEvent, String> {
    List<ActivityEvent> findByCircleIdOrderByCreatedAtDesc(String circleId);
}
