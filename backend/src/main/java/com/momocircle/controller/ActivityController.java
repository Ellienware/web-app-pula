package com.momocircle.controller;

import com.momocircle.dto.ActivityDtos.ActivityResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/circles/{circleId}/activity")
public class ActivityController {

    private final ActivityService activityService;
    private final CurrentUserService currentUser;

    public ActivityController(ActivityService activityService, CurrentUserService currentUser) {
        this.activityService = activityService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ActivityResponse> list(@PathVariable String circleId) {
        return activityService.list(circleId, currentUser.require());
    }
}
