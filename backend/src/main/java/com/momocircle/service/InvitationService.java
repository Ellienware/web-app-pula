package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleInvitation;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.InvitationStatus;
import com.momocircle.dto.InvitationDtos.CreateInvitationRequest;
import com.momocircle.dto.InvitationDtos.InvitationResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleInvitationRepository;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InvitationService {

    private final CircleInvitationRepository invitationRepository;
    private final CircleMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CircleService circleService;
    private final ActivityService activityService;

    public InvitationService(CircleInvitationRepository invitationRepository,
                             CircleMembershipRepository membershipRepository,
                             UserRepository userRepository,
                             CircleService circleService,
                             ActivityService activityService) {
        this.invitationRepository = invitationRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.circleService = circleService;
        this.activityService = activityService;
    }

    @Transactional
    public InvitationResponse invite(String circleId, User actor, CreateInvitationRequest req) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        circleService.validateRoleForCircle(circleId, req.role());
        Circle circle = membership.getCircle();

        String email = req.email().trim().toLowerCase();

        userRepository.findByEmail(email).ifPresent(existing -> {
            if (membershipRepository.existsByCircleIdAndUserId(circleId, existing.getId())) {
                throw ApiException.conflict("That person is already a member of this circle");
            }
        });

        if (invitationRepository.existsByCircleIdAndInvitedEmailAndStatus(
                circleId, email, InvitationStatus.PENDING)) {
            throw ApiException.conflict("An invitation is already pending for that email");
        }

        CircleInvitation invitation = new CircleInvitation();
        invitation.setCircle(circle);
        invitation.setInvitedBy(actor);
        invitation.setInvitedEmail(email);
        invitation.setRole(req.role() == null ? CircleRole.MEMBER : req.role());
        invitation.setToken(UUID.randomUUID().toString().replace("-", ""));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation = invitationRepository.save(invitation);

        activityService.record(circle, actor, ActivityType.MEMBER_INVITED,
                actor.getFullName() + " invited " + email);

        return toResponse(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listForCircle(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        return invitationRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listMine(User user) {
        return invitationRepository
                .findByInvitedEmailAndStatusOrderByCreatedAtDesc(
                        user.getEmail().toLowerCase(), InvitationStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public InvitationResponse accept(String token, User user) {
        CircleInvitation invitation = requirePending(token, user);

        if (!membershipRepository.existsByCircleIdAndUserId(invitation.getCircle().getId(), user.getId())) {
            CircleMembership membership = new CircleMembership();
            membership.setCircle(invitation.getCircle());
            membership.setUser(user);
            membership.setRole(invitation.getRole());
            membershipRepository.save(membership);

            activityService.record(invitation.getCircle(), user, ActivityType.MEMBER_JOINED,
                    user.getFullName() + " joined via invitation");
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation = invitationRepository.save(invitation);

        activityService.record(invitation.getCircle(), user, ActivityType.INVITATION_ACCEPTED,
                user.getFullName() + " accepted the invitation");

        return toResponse(invitation);
    }

    @Transactional
    public InvitationResponse decline(String token, User user) {
        CircleInvitation invitation = requirePending(token, user);
        invitation.setStatus(InvitationStatus.DECLINED);
        invitation = invitationRepository.save(invitation);

        activityService.record(invitation.getCircle(), user, ActivityType.INVITATION_DECLINED,
                user.getFullName() + " declined the invitation");

        return toResponse(invitation);
    }

    @Transactional
    public InvitationResponse cancel(String circleId, String invitationId, User actor) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);

        CircleInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> ApiException.notFound("Invitation not found"));
        if (!invitation.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Invitation does not belong to this circle");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw ApiException.badRequest("Only pending invitations can be cancelled");
        }
        invitation.setStatus(InvitationStatus.CANCELLED);
        invitation = invitationRepository.save(invitation);
        return toResponse(invitation);
    }

    private CircleInvitation requirePending(String token, User user) {
        CircleInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> ApiException.notFound("Invitation not found"));

        if (!invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail())) {
            throw ApiException.forbidden("This invitation was sent to a different email address");
        }
        if (invitation.getExpiresAt() != null && Instant.now().isAfter(invitation.getExpiresAt())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.save(invitation);
            throw ApiException.badRequest("This invitation has expired");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw ApiException.badRequest("This invitation is no longer pending");
        }
        return invitation;
    }

    private InvitationResponse toResponse(CircleInvitation i) {
        return new InvitationResponse(
                i.getId(),
                i.getCircle().getId(),
                i.getCircle().getName(),
                i.getInvitedEmail(),
                i.getInvitedBy().getFullName(),
                i.getRole(),
                i.getToken(),
                i.getStatus(),
                i.getExpiresAt(),
                i.getCreatedAt());
    }
}
