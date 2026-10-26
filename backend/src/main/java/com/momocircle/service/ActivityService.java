package com.momocircle.service;

import com.momocircle.domain.ActivityEvent;
import com.momocircle.domain.Circle;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.dto.ActivityDtos.ActivityResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ActivityRepository;
import com.momocircle.repository.CircleMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Central place to append and read circle activity events. {@link #record} is
 * designed to be called from within an existing transaction so the event is
 * committed atomically with the action that produced it.
 */
@Service
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final CircleMembershipRepository membershipRepository;

    public ActivityService(ActivityRepository activityRepository,
                           CircleMembershipRepository membershipRepository) {
        this.activityRepository = activityRepository;
        this.membershipRepository = membershipRepository;
    }

    public void record(Circle circle, User actor, ActivityType type, String message) {
        ActivityEvent event = new ActivityEvent();
        event.setCircle(circle);
        event.setActor(actor);
        event.setType(type);
        event.setMessage(message);
        activityRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> list(String circleId, User viewer) {
        if (!membershipRepository.existsByCircleIdAndUserId(circleId, viewer.getId())) {
            throw ApiException.forbidden("You are not a member of this circle");
        }
        return activityRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ActivityResponse toResponse(ActivityEvent e) {
        return new ActivityResponse(
                e.getId(),
                e.getCircle().getId(),
                e.getActor() == null ? null : e.getActor().getId(),
                e.getActor() == null ? "System" : e.getActor().getFullName(),
                e.getType(),
                e.getMessage(),
                e.getCreatedAt());
    }
}
