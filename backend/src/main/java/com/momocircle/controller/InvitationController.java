package com.momocircle.controller;

import com.momocircle.dto.InvitationDtos.InvitationResponse;
import com.momocircle.dto.InvitationDtos.RedeemInvitationRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The invitee's own inbox: view pending invites and accept/decline them. */
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    private final InvitationService invitationService;
    private final CurrentUserService currentUser;

    public InvitationController(InvitationService invitationService, CurrentUserService currentUser) {
        this.invitationService = invitationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<InvitationResponse> mine() {
        return invitationService.listMine(currentUser.require());
    }

    @PostMapping("/accept")
    public InvitationResponse accept(@Valid @RequestBody RedeemInvitationRequest req) {
        return invitationService.accept(req.token(), currentUser.require());
    }

    @PostMapping("/decline")
    public InvitationResponse decline(@Valid @RequestBody RedeemInvitationRequest req) {
        return invitationService.decline(req.token(), currentUser.require());
    }
}
