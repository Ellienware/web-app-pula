package com.momocircle.controller;

import com.momocircle.dto.CircleDtos.AddMemberRequest;
import com.momocircle.dto.CircleDtos.CircleResponse;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.dto.CircleDtos.MemberResponse;
import com.momocircle.dto.ContributionDtos.ContributeRequest;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.dto.ContributionDtos.CreatePaymentRequestRequest;
import com.momocircle.dto.ContributionDtos.PaymentRequestResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.CircleService;
import com.momocircle.service.ContributionService;
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
@RequestMapping("/api/circles")
public class CircleController {

    private final CircleService circleService;
    private final ContributionService contributionService;
    private final CurrentUserService currentUser;

    public CircleController(CircleService circleService,
                           ContributionService contributionService,
                           CurrentUserService currentUser) {
        this.circleService = circleService;
        this.contributionService = contributionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<CircleResponse> list() {
        return circleService.listForUser(currentUser.require());
    }

    @PostMapping
    public ResponseEntity<CircleResponse> create(@Valid @RequestBody CreateCircleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(circleService.create(currentUser.require(), req));
    }

    @GetMapping("/{circleId}")
    public CircleResponse get(@PathVariable String circleId) {
        return circleService.get(circleId, currentUser.require());
    }

    @GetMapping("/{circleId}/members")
    public List<MemberResponse> members(@PathVariable String circleId) {
        return circleService.listMembers(circleId, currentUser.require());
    }

    @PostMapping("/{circleId}/members")
    public ResponseEntity<MemberResponse> addMember(@PathVariable String circleId,
                                                    @Valid @RequestBody AddMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(circleService.addMember(circleId, currentUser.require(), req));
    }

    @GetMapping("/{circleId}/contributions")
    public List<ContributionResponse> contributions(@PathVariable String circleId) {
        return contributionService.list(circleId, currentUser.require());
    }

    @PostMapping("/{circleId}/contributions")
    public ResponseEntity<ContributionResponse> contribute(@PathVariable String circleId,
                                                           @Valid @RequestBody ContributeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contributionService.contribute(circleId, currentUser.require(), req));
    }

    @GetMapping("/{circleId}/payment-requests")
    public List<PaymentRequestResponse> paymentRequests(@PathVariable String circleId) {
        return contributionService.listPaymentRequests(circleId, currentUser.require());
    }

    @PostMapping("/{circleId}/payment-requests")
    public ResponseEntity<PaymentRequestResponse> createPaymentRequest(@PathVariable String circleId,
                                                                       @Valid @RequestBody CreatePaymentRequestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contributionService.createPaymentRequest(circleId, currentUser.require(), req));
    }
}
