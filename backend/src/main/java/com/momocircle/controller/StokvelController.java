package com.momocircle.controller;

import com.momocircle.domain.enums.VoteDecision;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** All routes: authenticated, membership-checked in the service, role-checked per action. */
@RestController
@RequestMapping("/api/stokvels")
public class StokvelController {

    private final StokvelService stokvels;
    private final StokvelContributionService contributions;
    private final StokvelRotationService rotation;
    private final StokvelRequestService requests;
    private final StokvelInsightService insights;
    private final CurrentUserService currentUser;

    public StokvelController(StokvelService stokvels, StokvelContributionService contributions,
                             StokvelRotationService rotation, StokvelRequestService requests,
                             StokvelInsightService insights, CurrentUserService currentUser) {
        this.stokvels = stokvels;
        this.contributions = contributions;
        this.rotation = rotation;
        this.requests = requests;
        this.insights = insights;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<StokvelOverview> create(@Valid @RequestBody CreateStokvelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stokvels.create(currentUser.require(), req));
    }

    @GetMapping
    public List<StokvelSummary> list() {
        return stokvels.listForUser(currentUser.require());
    }

    @GetMapping("/{circleId}")
    public StokvelOverview overview(@PathVariable String circleId) {
        return stokvels.overview(circleId, currentUser.require());
    }

    @PutMapping("/{circleId}/settings")
    public SettingsView settings(@PathVariable String circleId, @Valid @RequestBody UpdateSettingsRequest req) {
        return stokvels.updateSettings(circleId, currentUser.require(), req);
    }

    @GetMapping("/{circleId}/progress")
    public GoalProgress progress(@PathVariable String circleId) {
        return stokvels.goalProgress(circleId, currentUser.require());
    }

    // contributions
    @PostMapping("/{circleId}/contributions")
    public ResponseEntity<ContributionResponse> contribute(@PathVariable String circleId,
                                                           @Valid @RequestBody StokvelContributeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contributions.contribute(circleId, currentUser.require(), req));
    }

    @GetMapping("/{circleId}/contributions/status")
    public ContributionStatusView contributionStatus(@PathVariable String circleId) {
        return contributions.status(circleId, currentUser.require());
    }

    // rotation
    @GetMapping("/{circleId}/rotation")
    public RotationView getRotation(@PathVariable String circleId) {
        return rotation.get(circleId, currentUser.require());
    }

    @PutMapping("/{circleId}/rotation/order")
    public RotationView setOrder(@PathVariable String circleId, @Valid @RequestBody SetRotationOrderRequest req) {
        return rotation.setOrder(circleId, currentUser.require(), req);
    }

    @PostMapping("/{circleId}/rotation/payouts")
    public RotationView recordPayout(@PathVariable String circleId, @Valid @RequestBody RecordPayoutRequest req) {
        return rotation.recordPayout(circleId, currentUser.require(), req);
    }

    // requests
    @GetMapping("/{circleId}/requests")
    public List<RequestView> listRequests(@PathVariable String circleId) {
        return requests.list(circleId, currentUser.require());
    }

    @PostMapping("/{circleId}/requests")
    public ResponseEntity<RequestView> createRequest(@PathVariable String circleId,
                                                     @Valid @RequestBody CreateStokvelRequestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.create(circleId, currentUser.require(), req));
    }

    @GetMapping("/{circleId}/requests/{requestId}")
    public RequestView getRequest(@PathVariable String circleId, @PathVariable String requestId) {
        return requests.get(circleId, requestId, currentUser.require());
    }

    @PostMapping("/{circleId}/requests/{requestId}/approve")
    public RequestView approve(@PathVariable String circleId, @PathVariable String requestId,
                               @Valid @RequestBody(required = false) VoteRequest body) {
        return requests.vote(circleId, requestId, currentUser.require(), VoteDecision.APPROVE,
                body == null ? null : body.comment());
    }

    @PostMapping("/{circleId}/requests/{requestId}/reject")
    public RequestView reject(@PathVariable String circleId, @PathVariable String requestId,
                              @Valid @RequestBody(required = false) VoteRequest body) {
        return requests.vote(circleId, requestId, currentUser.require(), VoteDecision.REJECT,
                body == null ? null : body.comment());
    }

    @PostMapping("/{circleId}/requests/{requestId}/cancel")
    public RequestView cancel(@PathVariable String circleId, @PathVariable String requestId) {
        return requests.cancel(circleId, requestId, currentUser.require());
    }

    @PostMapping("/{circleId}/requests/{requestId}/link-order")
    public RequestView linkOrder(@PathVariable String circleId, @PathVariable String requestId,
                                 @Valid @RequestBody LinkOrderRequest req) {
        return requests.linkOrder(circleId, requestId, currentUser.require(), req);
    }

    @PostMapping("/{circleId}/requests/{requestId}/record-execution")
    public RequestView recordExecution(@PathVariable String circleId, @PathVariable String requestId,
                                       @Valid @RequestBody RecordExecutionRequest req) {
        return requests.recordExecution(circleId, requestId, currentUser.require(), req);
    }

    // read-only assistant helper
    @GetMapping("/{circleId}/insights")
    public InsightsView getInsights(@PathVariable String circleId,
                                    @RequestParam(required = false) BigDecimal scenarioAmount) {
        return insights.insights(circleId, currentUser.require(), scenarioAmount);
    }
}
