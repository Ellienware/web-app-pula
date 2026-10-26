package com.momocircle.controller;

import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.GoalDtos.GoalResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.GoalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/circles/{circleId}/goals")
public class GoalController {

    private final GoalService goalService;
    private final CurrentUserService currentUser;

    public GoalController(GoalService goalService, CurrentUserService currentUser) {
        this.goalService = goalService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<GoalResponse> list(@PathVariable String circleId) {
        return goalService.list(circleId, currentUser.require());
    }

    @PostMapping
    public ResponseEntity<GoalResponse> create(@PathVariable String circleId,
                                               @Valid @RequestBody CreateGoalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(goalService.create(circleId, currentUser.require(), req));
    }

    @PostMapping("/{goalId}/close")
    public GoalResponse close(@PathVariable String circleId, @PathVariable String goalId) {
        return goalService.close(circleId, goalId, currentUser.require());
    }
}
