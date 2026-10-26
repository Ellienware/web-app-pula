package com.momocircle.controller;

import com.momocircle.domain.enums.RecurringStatus;
import com.momocircle.dto.RecurringDtos.CreateRecurringRequest;
import com.momocircle.dto.RecurringDtos.RecurringPlanResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.RecurringContributionService;
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
@RequestMapping("/api/circles/{circleId}/recurring")
public class RecurringController {

    private final RecurringContributionService recurringService;
    private final CurrentUserService currentUser;

    public RecurringController(RecurringContributionService recurringService, CurrentUserService currentUser) {
        this.recurringService = recurringService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<RecurringPlanResponse> list(@PathVariable String circleId) {
        return recurringService.list(circleId, currentUser.require());
    }

    @PostMapping
    public ResponseEntity<RecurringPlanResponse> create(@PathVariable String circleId,
                                                        @Valid @RequestBody CreateRecurringRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recurringService.create(circleId, currentUser.require(), req));
    }

    @PostMapping("/{planId}/generate")
    public RecurringPlanResponse generate(@PathVariable String circleId, @PathVariable String planId) {
        return recurringService.generateNextPeriod(circleId, planId, currentUser.require());
    }

    @PostMapping("/{planId}/pause")
    public RecurringPlanResponse pause(@PathVariable String circleId, @PathVariable String planId) {
        return recurringService.setStatus(circleId, planId, currentUser.require(), RecurringStatus.PAUSED);
    }

    @PostMapping("/{planId}/resume")
    public RecurringPlanResponse resume(@PathVariable String circleId, @PathVariable String planId) {
        return recurringService.setStatus(circleId, planId, currentUser.require(), RecurringStatus.ACTIVE);
    }

    @PostMapping("/{planId}/cancel")
    public RecurringPlanResponse cancel(@PathVariable String circleId, @PathVariable String planId) {
        return recurringService.setStatus(circleId, planId, currentUser.require(), RecurringStatus.CANCELLED);
    }

    @PostMapping("/dues/{dueId}/pay")
    public RecurringPlanResponse payDue(@PathVariable String circleId, @PathVariable String dueId) {
        return recurringService.payDue(circleId, dueId, currentUser.require());
    }
}
