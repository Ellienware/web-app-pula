package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.CircleInvitation;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.InvitationStatus;
import com.momocircle.dto.InvitationDtos.CreateInvitationRequest;
import com.momocircle.dto.InvitationDtos.InvitationResponse;
import com.momocircle.exception.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE 2A: current invitation behaviour, exactly as implemented. */
class InvitationCharacterisationTest {

    private SavingsFixture f;
    private User owner, admin, member, invitee, stranger;
    private String circleId;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); admin = f.user("Admin"); member = f.user("Member");
        invitee = f.user("Invitee"); stranger = f.user("Stranger");
        circleId = f.newCircle(owner, "Savers", CircleType.SAVINGS_GOAL, null).id();
        f.join(circleId, admin, CircleRole.ADMIN);
        f.join(circleId, member, CircleRole.MEMBER);
    }

    private InvitationResponse invite(User actor, String email, CircleRole role) {
        return f.invitationService.invite(circleId, actor, new CreateInvitationRequest(email, role));
    }

    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    @Test
    void anAdminCreatesAPendingInvitationWithASecretToken() {
        InvitationResponse i = invite(admin, " INVITEE@Example.com ", null);
        assertEquals(InvitationStatus.PENDING, i.status());
        assertEquals("invitee@example.com", i.invitedEmail(), "email trimmed and lower-cased");
        assertEquals(CircleRole.MEMBER, i.role(), "default role");
        assertEquals(32, i.token().length());
        assertNull(i.expiresAt(), "an invitation never expires unless something sets expiresAt (nothing does)");
        assertEquals(1, f.activityCount(ActivityType.MEMBER_INVITED));
    }

    @Test
    void membersCannotInviteAndNonMembersCannotEither() {
        assertEquals(403, status(() -> invite(member, "x@example.com", null)));
        assertEquals(403, status(() -> invite(stranger, "x@example.com", null)));
    }

    @Test
    void existingMembersAndDuplicatePendingInvitesAreRefused() {
        assertEquals(409, status(() -> invite(owner, "member@example.com", null)));
        invite(owner, "invitee@example.com", null);
        assertEquals(409, status(() -> invite(owner, "INVITEE@example.com", null)));
    }

    @Test
    void FINDING_anAdminCanInviteSomeoneAsOwner() {
        assertEquals(CircleRole.OWNER, invite(admin, "invitee@example.com", CircleRole.OWNER).role());
    }

    @Test
    void acceptingCreatesTheMembershipWithTheInvitedRole() {
        InvitationResponse i = invite(owner, "invitee@example.com", CircleRole.ADMIN);
        InvitationResponse accepted = f.invitationService.accept(i.token(), invitee);
        assertEquals(InvitationStatus.ACCEPTED, accepted.status());
        assertEquals(CircleRole.ADMIN, f.membershipOf(circleId, invitee).orElseThrow().getRole());
        assertEquals(1, f.activityCount(ActivityType.INVITATION_ACCEPTED));
        assertEquals(1, f.activityCount(ActivityType.MEMBER_JOINED));
    }

    @Test
    void anInvitationCanOnlyBeAcceptedByTheInvitedEmailAndOnlyOnce() {
        InvitationResponse i = invite(owner, "invitee@example.com", null);
        assertEquals(403, status(() -> f.invitationService.accept(i.token(), stranger)), "the token alone is not enough: the email must match");
        assertEquals(404, status(() -> f.invitationService.accept("bogus", invitee)));
        f.invitationService.accept(i.token(), invitee);
        assertEquals(400, status(() -> f.invitationService.accept(i.token(), invitee)), "no longer pending");
        assertEquals(1, f.memberships.stream().filter(m -> m.getUser().getId().equals(invitee.getId())).count());
    }

    @Test
    void anExpiredInvitationIsMarkedExpiredAndRefused() {
        InvitationResponse i = invite(owner, "invitee@example.com", null);
        CircleInvitation entity = f.invitations.get(i.id());
        entity.setExpiresAt(Instant.now().minusSeconds(5));
        assertEquals(400, status(() -> f.invitationService.accept(i.token(), invitee)));
        assertEquals(InvitationStatus.EXPIRED, entity.getStatus());
        assertFalse(f.membershipOf(circleId, invitee).isPresent());
    }

    @Test
    void decliningRecordsItAndCreatesNoMembership() {
        InvitationResponse i = invite(owner, "invitee@example.com", null);
        assertEquals(InvitationStatus.DECLINED, f.invitationService.decline(i.token(), invitee).status());
        assertFalse(f.membershipOf(circleId, invitee).isPresent());
        assertEquals(400, status(() -> f.invitationService.decline(i.token(), invitee)), "cannot decline twice");
        assertEquals(1, f.activityCount(ActivityType.INVITATION_DECLINED));
    }

    @Test
    void acceptingWhenAlreadyAMemberDoesNotDuplicateTheMembership() {
        InvitationResponse i = invite(owner, "invitee@example.com", null);
        f.join(circleId, invitee, CircleRole.MEMBER); // became a member by another route after the invite
        f.invitationService.accept(i.token(), invitee);
        assertEquals(1, f.memberships.stream().filter(m -> m.getUser().getId().equals(invitee.getId())).count());
    }

    @Test
    void adminsCanCancelPendingInvitationsOnly() {
        InvitationResponse i = invite(owner, "invitee@example.com", null);
        assertEquals(403, status(() -> f.invitationService.cancel(circleId, i.id(), member)));
        assertEquals(InvitationStatus.CANCELLED, f.invitationService.cancel(circleId, i.id(), admin).status());
        assertEquals(400, status(() -> f.invitationService.cancel(circleId, i.id(), admin)), "only pending can be cancelled");
        assertEquals(400, status(() -> f.invitationService.accept(i.token(), invitee)), "a cancelled invitation cannot be accepted");
        assertEquals(404, status(() -> f.invitationService.cancel(circleId, "nope", admin)));
    }

    @Test
    void invitationListsAreMemberOnlyAndMineShowsOnlyPendingForMyEmail() {
        invite(owner, "invitee@example.com", null);
        assertEquals(1, f.invitationService.listForCircle(circleId, member).size());
        assertEquals(403, status(() -> f.invitationService.listForCircle(circleId, stranger)));
        assertEquals(1, f.invitationService.listMine(invitee).size());
        assertEquals(0, f.invitationService.listMine(stranger).size());
    }
}
