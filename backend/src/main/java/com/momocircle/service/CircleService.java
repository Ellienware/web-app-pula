package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.CircleDtos.AddMemberRequest;
import com.momocircle.dto.CircleDtos.CircleResponse;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.dto.CircleDtos.MemberResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.CircleRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class CircleService {

    private final CircleRepository circleRepository;
    private final CircleMembershipRepository membershipRepository;
    private final ContributionRepository contributionRepository;
    private final UserRepository userRepository;
    private final ActivityService activityService;
    private final FinancialValidationService financialValidationService;

    public CircleService(CircleRepository circleRepository,
                         CircleMembershipRepository membershipRepository,
                         ContributionRepository contributionRepository,
                         UserRepository userRepository,
                         ActivityService activityService,
                         FinancialValidationService financialValidationService) {
        this.circleRepository = circleRepository;
        this.membershipRepository = membershipRepository;
        this.contributionRepository = contributionRepository;
        this.userRepository = userRepository;
        this.activityService = activityService;
        this.financialValidationService = financialValidationService;
    }

    /** Optional so existing constructors/tests are unchanged; null means "no stokvel rules". */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StokvelMembershipGuard stokvelGuard;

    @Transactional
    public CircleResponse create(User owner, CreateCircleRequest req) {
        if (req.type() == com.momocircle.domain.enums.CircleType.STOKVEL) {
            throw ApiException.badRequest("Create a stokvel through /api/stokvels");
        }
        return createInternal(owner, req);
    }

    /** Used by StokvelService only: same creation path as an ordinary circle (owner membership + activity). */
    @Transactional
    public CircleResponse createForStokvel(User owner, CreateCircleRequest req) {
        return createInternal(owner, req);
    }

    private CircleResponse createInternal(User owner, CreateCircleRequest req) {
        String currency = req.currency().trim().toUpperCase();
        financialValidationService.requireValidCurrency(currency);
        if (req.goalAmount() != null) {
            financialValidationService.requireValidAmount(req.goalAmount(), "goalAmount");
        }

        Circle circle = new Circle();
        circle.setName(req.name().trim());
        circle.setDescription(req.description());
        circle.setType(req.type());
        circle.setCurrency(currency);
        circle.setGoalAmount(req.goalAmount());
        circle.setOwner(owner);
        circle = circleRepository.save(circle);

        CircleMembership membership = new CircleMembership();
        membership.setCircle(circle);
        membership.setUser(owner);
        membership.setRole(CircleRole.OWNER);
        membershipRepository.save(membership);

        activityService.record(circle, owner, ActivityType.CIRCLE_CREATED,
                owner.getFullName() + " created the circle");

        return toResponse(circle, owner);
    }

    @Transactional(readOnly = true)
    public List<CircleResponse> listForUser(User user) {
        return membershipRepository.findByUserId(user.getId()).stream()
                .map(m -> toResponse(m.getCircle(), user))
                .toList();
    }

    @Transactional(readOnly = true)
    public CircleResponse get(String circleId, User viewer) {
        Circle circle = requireMembership(circleId, viewer).getCircle();
        return toResponse(circle, viewer);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(String circleId, User viewer) {
        requireMembership(circleId, viewer);
        return membershipRepository.findByCircleId(circleId).stream()
                .map(m -> new MemberResponse(
                        m.getId(),
                        m.getUser().getId(),
                        m.getUser().getFullName(),
                        m.getUser().getEmail(),
                        m.getRole()))
                .toList();
    }

    @Transactional
    public MemberResponse addMember(String circleId, User actor, AddMemberRequest req) {
        CircleMembership actorMembership = requireMembership(circleId, actor);
        requireAdmin(actorMembership);
        if (stokvelGuard != null) {
            stokvelGuard.validateRole(circleId, req.role());
        }

        User newUser = userRepository.findByEmail(req.email().trim().toLowerCase())
                .orElseThrow(() -> ApiException.notFound("No user with that email"));

        if (membershipRepository.existsByCircleIdAndUserId(circleId, newUser.getId())) {
            throw ApiException.conflict("User is already a member of this circle");
        }

        CircleMembership membership = new CircleMembership();
        membership.setCircle(actorMembership.getCircle());
        membership.setUser(newUser);
        membership.setRole(req.role() == null ? CircleRole.MEMBER : req.role());
        membership = membershipRepository.save(membership);

        activityService.record(actorMembership.getCircle(), actor, ActivityType.MEMBER_JOINED,
                newUser.getFullName() + " was added to the circle");

        return new MemberResponse(
                membership.getId(),
                newUser.getId(),
                newUser.getFullName(),
                newUser.getEmail(),
                membership.getRole());
    }

    /** Admin removes another member. Cannot remove the circle owner or oneself. */
    @Transactional
    public void removeMember(String circleId, String membershipId, User actor) {
        CircleMembership actorMembership = requireMembership(circleId, actor);
        requireAdmin(actorMembership);

        CircleMembership target = requireMembershipInCircle(circleId, membershipId);
        if (target.getRole() == CircleRole.OWNER) {
            throw ApiException.badRequest("The circle owner cannot be removed");
        }
        if (target.getUser().getId().equals(actor.getId())) {
            throw ApiException.badRequest("Use leave to remove yourself");
        }

        if (stokvelGuard != null) {
            stokvelGuard.beforeMemberLeaves(circleId, target.getUser().getId());
        }
        String name = target.getUser().getFullName();
        membershipRepository.delete(target);
        activityService.record(actorMembership.getCircle(), actor, ActivityType.MEMBER_REMOVED,
                name + " was removed from the circle");
    }

    /** A member leaves the circle. The owner must transfer ownership first. */
    @Transactional
    public void leaveCircle(String circleId, User actor) {
        CircleMembership membership = requireMembership(circleId, actor);
        if (membership.getRole() == CircleRole.OWNER) {
            throw ApiException.badRequest(
                    "The owner cannot leave. Transfer ownership to another admin first.");
        }
        if (stokvelGuard != null) {
            stokvelGuard.beforeMemberLeaves(circleId, actor.getId());
        }
        membershipRepository.delete(membership);
        activityService.record(membership.getCircle(), actor, ActivityType.MEMBER_LEFT,
                actor.getFullName() + " left the circle");
    }

    /** Owner promotes/demotes a member between ADMIN and MEMBER. */
    @Transactional
    public MemberResponse changeRole(String circleId, String membershipId, User actor, CircleRole role) {
        CircleMembership actorMembership = requireMembership(circleId, actor);
        if (actorMembership.getRole() != CircleRole.OWNER) {
            throw ApiException.forbidden("Only the circle owner can change member roles");
        }
        if (role == CircleRole.OWNER) {
            throw ApiException.badRequest("Ownership transfer is not supported here");
        }

        if (stokvelGuard != null) {
            stokvelGuard.validateRole(circleId, role);
        }
        CircleMembership target = requireMembershipInCircle(circleId, membershipId);
        if (target.getRole() == CircleRole.OWNER) {
            throw ApiException.badRequest("The owner's role cannot be changed");
        }

        target.setRole(role);
        target = membershipRepository.save(target);
        activityService.record(actorMembership.getCircle(), actor, ActivityType.MEMBER_ROLE_CHANGED,
                target.getUser().getFullName() + " is now " + role.name().toLowerCase());

        return new MemberResponse(
                target.getId(),
                target.getUser().getId(),
                target.getUser().getFullName(),
                target.getUser().getEmail(),
                target.getRole());
    }

    private CircleMembership requireMembershipInCircle(String circleId, String membershipId) {
        CircleMembership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
        if (!membership.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Member does not belong to this circle");
        }
        return membership;
    }

    // --- Shared helpers used by other services ---

    public CircleMembership requireMembership(String circleId, User user) {
        return membershipRepository.findByCircleIdAndUserId(circleId, user.getId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this circle"));
    }

    /** Role validation for invitations (same rules as addMember). */
    public void validateRoleForCircle(String circleId, CircleRole role) {
        if (stokvelGuard != null) {
            stokvelGuard.validateRole(circleId, role);
        }
    }

    /** All memberships of a circle. Used by services that fan out to every member. */
    public List<CircleMembership> membershipsOf(String circleId) {
        return membershipRepository.findByCircleId(circleId);
    }

    /** Legacy admin check: OWNER and ADMIN only. TREASURER/CHAIRPERSON are NOT admins for the existing Savings features. */
    public void requireAdmin(CircleMembership membership) {
        if (membership.getRole() != CircleRole.OWNER && membership.getRole() != CircleRole.ADMIN) {
            throw ApiException.forbidden("Only circle admins can perform this action");
        }
    }

    public CircleResponse toResponse(Circle circle, User viewer) {
        BigDecimal total = contributionRepository.sumConfirmedAmount(circle.getId(), PaymentStatus.SUCCESSFUL);
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        Integer progress = null;
        if (circle.getGoalAmount() != null && circle.getGoalAmount().compareTo(BigDecimal.ZERO) > 0) {
            progress = total.multiply(BigDecimal.valueOf(100))
                    .divide(circle.getGoalAmount(), 0, RoundingMode.FLOOR)
                    .min(BigDecimal.valueOf(100))
                    .intValue();
        }
        long memberCount = membershipRepository.countByCircleId(circle.getId());
        CircleRole viewerRole = membershipRepository
                .findByCircleIdAndUserId(circle.getId(), viewer.getId())
                .map(CircleMembership::getRole)
                .orElse(null);

        return new CircleResponse(
                circle.getId(),
                circle.getName(),
                circle.getDescription(),
                circle.getType(),
                circle.getCurrency(),
                circle.getGoalAmount(),
                total,
                progress,
                memberCount,
                circle.getOwner().getId(),
                viewerRole);
    }
}
