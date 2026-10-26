package com.momocircle.controller;

import com.momocircle.dto.InvitationDtos.CreateInvitationRequest;
import com.momocircle.dto.InvitationDtos.InvitationResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.InvitationService;
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
@RequestMapping("/api/circles/{circleId}/invitations")
public class CircleInvitationController {

    private final InvitationService invitationService;
    private final CurrentUserService currentUser;

    public CircleInvitationController(InvitationService invitationService, CurrentUserService currentUser) {
        this.invitationService = invitationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<InvitationResponse> list(@PathVariable String circleId) {
        return invitationService.listForCircle(circleId, currentUser.require());
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@PathVariable String circleId,
                                                     @Valid @RequestBody CreateInvitationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(circleId, currentUser.require(), req));
    }

    @PostMapping("/{invitationId}/cancel")
    public InvitationResponse cancel(@PathVariable String circleId, @PathVariable String invitationId) {
        return invitationService.cancel(circleId, invitationId, currentUser.require());
    }
}
