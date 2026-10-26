package com.momocircle.controller;

import com.momocircle.domain.enums.RelatedOrderKind;
import com.momocircle.dto.SaveToBuyDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.SaveToBuyInsightService;
import com.momocircle.service.SaveToBuyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Authenticated; ownership / membership / role are enforced in the service. No route here pays or checks out. */
@RestController
@RequestMapping("/api/save-to-buy")
public class SaveToBuyController {

    private final SaveToBuyService service;
    private final SaveToBuyInsightService insights;
    private final CurrentUserService currentUser;

    public SaveToBuyController(SaveToBuyService service, SaveToBuyInsightService insights, CurrentUserService currentUser) {
        this.service = service;
        this.insights = insights;
        this.currentUser = currentUser;
    }

    @GetMapping("/goals")
    public List<EligibleGoal> goals(@RequestParam(defaultValue = "false") boolean all) {
        return service.eligibleGoals(currentUser.require(), all);
    }

    @PutMapping("/goals/{goalId}/intent")
    public EligibleGoal designate(@PathVariable String goalId, @Valid @RequestBody DesignateGoalRequest req) {
        return service.designateGoal(goalId, currentUser.require(), req);
    }

    @PostMapping
    public ResponseEntity<SaveToBuyView> create(@Valid @RequestBody CreateSaveToBuyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.require(), req));
    }

    @GetMapping
    public List<SaveToBuyView> mine() {
        return service.listMine(currentUser.require());
    }

    @GetMapping("/circle/{circleId}")
    public List<SaveToBuyView> forCircle(@PathVariable String circleId) {
        return service.listForCircle(circleId, currentUser.require());
    }

    @GetMapping("/{id}")
    public SaveToBuyView get(@PathVariable String id) {
        return service.get(id, currentUser.require());
    }

    @GetMapping("/by-order/{kind}/{orderId}")
    public SaveToBuyView byOrder(@PathVariable RelatedOrderKind kind, @PathVariable String orderId) {
        return service.forOrder(kind, orderId, currentUser.require());
    }

    @PostMapping("/{id}/confirm")
    public SaveToBuyView confirm(@PathVariable String id) {
        return service.confirm(id, currentUser.require());
    }

    @PostMapping("/{id}/submit")
    public SaveToBuyView submit(@PathVariable String id) {
        return service.submit(id, currentUser.require());
    }

    @PostMapping("/{id}/cancel")
    public SaveToBuyView cancel(@PathVariable String id) {
        return service.cancel(id, currentUser.require());
    }

    @PostMapping("/{id}/complete")
    public SaveToBuyView complete(@PathVariable String id, @Valid @RequestBody CompleteRequest req) {
        return service.complete(id, currentUser.require(), req);
    }

    @GetMapping("/{id}/insights")
    public InsightsView insights(@PathVariable String id) {
        return insights.insights(id, currentUser.require(), LocalDate.now());
    }
}
