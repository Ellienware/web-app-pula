package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.dto.CircleDtos.*;
import com.momocircle.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 2A: characterises the CURRENT behaviour of circles and memberships exactly as implemented. Where the
 * behaviour looks like a defect it is asserted as-is and flagged "FINDING"; nothing here was fixed.
 */
class CircleCharacterisationTest {

    private SavingsFixture f;
    private User owner, admin, member, outsider;
    private String circleId;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); admin = f.user("Admin"); member = f.user("Member"); outsider = f.user("Outsider");
        circleId = f.newCircle(owner, "Street Savers", CircleType.SAVINGS_GOAL, d("1000.00")).id();
        f.join(circleId, admin, CircleRole.ADMIN);
        f.join(circleId, member, CircleRole.MEMBER);
    }

    private ApiException denied(Runnable r) { return assertThrows(ApiException.class, r::run); }

    // ------------------------------------------------------------------ creation and retrieval

    @Test
    void creatingACircleMakesTheCreatorItsOwnerAndRecordsActivity() {
        CircleResponse c = f.circleService.create(member, new CreateCircleRequest("  Taxi Fund ", "d", CircleType.EXPENSE_POOL, "zar", null));
        assertEquals("Taxi Fund", c.name(), "name is trimmed");
        assertEquals("ZAR", c.currency(), "currency is upper-cased");
        assertEquals(CircleType.EXPENSE_POOL, c.type());
        assertEquals(CircleRole.OWNER, c.viewerRole());
        assertEquals(member.getId(), c.ownerId());
        assertEquals(1, c.memberCount());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(c.totalContributed()));
        assertNull(c.progressPercent(), "no goal amount => no progress percent");
        assertEquals(2, f.activityCount(ActivityType.CIRCLE_CREATED), "one from setUp, one from this test");
    }

    @Test
    void aGoalAmountGivesAProgressPercentStartingAtZero() {
        CircleResponse c = f.circleService.get(circleId, owner);
        assertEquals(d("1000.00"), c.goalAmount());
        assertEquals(0, c.progressPercent());
        assertEquals(3, c.memberCount());
    }

    @Test
    void anUnsupportedCurrencyIsRefused() {
        assertEquals(400, denied(() -> f.circleService.create(owner, new CreateCircleRequest("X", null, CircleType.SAVINGS_GOAL, "XAF", null))).getStatus().value());
        assertEquals(400, denied(() -> f.circleService.create(owner, new CreateCircleRequest("X", null, CircleType.SAVINGS_GOAL, " ", null))).getStatus().value());
    }

    @Test
    void aBadGoalAmountIsRefusedAtCreation() {
        for (String bad : new String[] {"0", "-1", "10.005"}) {
            assertEquals(400, denied(() -> f.circleService.create(owner, new CreateCircleRequest("X", null, CircleType.SAVINGS_GOAL, "ZAR", d(bad)))).getStatus().value(), bad);
        }
    }

    @Test
    void theRotatingTypeIsOnlyALabelItCreatesAnOrdinaryCircle() {
        CircleResponse r = f.circleService.create(owner, new CreateCircleRequest("Rotating", null, CircleType.ROTATING, "ZAR", null));
        assertEquals(CircleType.ROTATING, r.type());
        // No rotation order, schedule, cycle or beneficiary exists anywhere on the circle or its membership.
        assertEquals(1, f.memberships.stream().filter(m -> m.getCircle().getId().equals(r.id())).count());
    }

    @Test
    void listForUserReturnsOnlyTheCirclesTheUserBelongsTo() {
        f.newCircle(outsider, "Other", CircleType.EXPENSE_POOL, null);
        assertEquals(1, f.circleService.listForUser(member).size());
        assertEquals("Street Savers", f.circleService.listForUser(member).get(0).name());
        assertEquals(1, f.circleService.listForUser(outsider).size());
    }

    @Test
    void aMemberCanReadTheCircleAndItsMemberList() {
        assertEquals(CircleRole.MEMBER, f.circleService.get(circleId, member).viewerRole());
        List<MemberResponse> members = f.circleService.listMembers(circleId, member);
        assertEquals(3, members.size());
        assertTrue(members.stream().anyMatch(m -> m.role() == CircleRole.OWNER && m.userId().equals(owner.getId())));
    }

    @Test
    void aNonMemberCannotReadACircleAndAnUnknownIdIsAlsoForbiddenNotNotFound() {
        assertEquals(403, denied(() -> f.circleService.get(circleId, outsider)).getStatus().value());
        assertEquals(403, denied(() -> f.circleService.listMembers(circleId, outsider)).getStatus().value());
        assertEquals(403, denied(() -> f.circleService.get("does-not-exist", owner)).getStatus().value(),
                "an id that does not exist looks the same as a circle you are not in (good: no existence leak)");
    }

    // ------------------------------------------------------------------ permissions by role

    @Test
    void ownersAndAdminsPassTheAdminCheckPlainMembersDoNot() {
        f.circleService.requireAdmin(f.membershipOf(circleId, owner).orElseThrow());
        f.circleService.requireAdmin(f.membershipOf(circleId, admin).orElseThrow());
        assertEquals(403, denied(() -> f.circleService.requireAdmin(f.membershipOf(circleId, member).orElseThrow())).getStatus().value());
    }

    @Test
    void anAdminCanAddMembersDirectlyByEmailWithNoAcceptanceStep() {
        User newbie = f.user("Newbie");
        MemberResponse m = f.circleService.addMember(circleId, admin, new AddMemberRequest("  NEWBIE@example.com ", null));
        assertEquals(CircleRole.MEMBER, m.role(), "defaults to MEMBER");
        assertTrue(f.membershipOf(circleId, newbie).isPresent(), "added immediately, without consenting");
        assertEquals(1, f.activityCount(ActivityType.MEMBER_JOINED));
    }

    @Test
    void membersCannotAddMembersAndUnknownEmailsAndDuplicatesAreRefused() {
        f.user("Newbie");
        assertEquals(403, denied(() -> f.circleService.addMember(circleId, member, new AddMemberRequest("newbie@example.com", null))).getStatus().value());
        assertEquals(404, denied(() -> f.circleService.addMember(circleId, owner, new AddMemberRequest("nobody@example.com", null))).getStatus().value());
        assertEquals(409, denied(() -> f.circleService.addMember(circleId, owner, new AddMemberRequest("member@example.com", null))).getStatus().value());
    }

    @Test
    void FINDING_anAdminCanAddSomeoneWithTheOwnerRole() {
        f.user("Newbie");
        MemberResponse m = f.circleService.addMember(circleId, admin, new AddMemberRequest("newbie@example.com", CircleRole.OWNER));
        assertEquals(CircleRole.OWNER, m.role(),
                "FINDING: addMember does not stop an ADMIN granting OWNER, although changeRole refuses OWNER. A second owner results.");
    }

    @Test
    void FINDING_anAdminCanAddAnotherAdmin() {
        f.user("Newbie");
        assertEquals(CircleRole.ADMIN, f.circleService.addMember(circleId, admin, new AddMemberRequest("newbie@example.com", CircleRole.ADMIN)).role());
    }

    @Test
    void adminsAndOwnersCanRemoveMembersAndTheRemovedPersonLosesAccess() {
        String membershipId = f.membershipOf(circleId, member).orElseThrow().getId();
        f.circleService.removeMember(circleId, membershipId, admin);
        assertFalse(f.membershipOf(circleId, member).isPresent());
        assertEquals(403, denied(() -> f.circleService.get(circleId, member)).getStatus().value());
        assertEquals(1, f.activityCount(ActivityType.MEMBER_REMOVED));
    }

    @Test
    void removalRulesOwnerSelfAndPlainMembers() {
        String ownerM = f.membershipOf(circleId, owner).orElseThrow().getId();
        String adminM = f.membershipOf(circleId, admin).orElseThrow().getId();
        String memberM = f.membershipOf(circleId, member).orElseThrow().getId();
        assertEquals(400, denied(() -> f.circleService.removeMember(circleId, ownerM, admin)).getStatus().value(), "the owner cannot be removed");
        assertEquals(400, denied(() -> f.circleService.removeMember(circleId, adminM, admin)).getStatus().value(), "use leave to remove yourself");
        assertEquals(403, denied(() -> f.circleService.removeMember(circleId, adminM, member)).getStatus().value());
        assertEquals(404, denied(() -> f.circleService.removeMember(circleId, "nope", admin)).getStatus().value());
        assertTrue(f.membershipOf(circleId, member).isPresent() && memberM != null);
    }

    @Test
    void FINDING_anAdminCanRemoveAnotherAdmin() {
        User admin2 = f.user("Admin2");
        String id = f.join(circleId, admin2, CircleRole.ADMIN).getId();
        f.circleService.removeMember(circleId, id, admin);
        assertFalse(f.membershipOf(circleId, admin2).isPresent());
    }

    @Test
    void membersCanLeaveButTheOwnerCannot() {
        f.circleService.leaveCircle(circleId, member);
        assertFalse(f.membershipOf(circleId, member).isPresent());
        assertEquals(1, f.activityCount(ActivityType.MEMBER_LEFT));
        assertEquals(400, denied(() -> f.circleService.leaveCircle(circleId, owner)).getStatus().value());
        assertEquals(403, denied(() -> f.circleService.leaveCircle(circleId, outsider)).getStatus().value());
    }

    @Test
    void onlyTheOwnerCanChangeRolesAndNeverToOwnerOrOfTheOwner() {
        String memberM = f.membershipOf(circleId, member).orElseThrow().getId();
        String ownerM = f.membershipOf(circleId, owner).orElseThrow().getId();
        assertEquals(403, denied(() -> f.circleService.changeRole(circleId, memberM, admin, CircleRole.ADMIN)).getStatus().value(), "an ADMIN cannot change roles");
        assertEquals(400, denied(() -> f.circleService.changeRole(circleId, memberM, owner, CircleRole.OWNER)).getStatus().value());
        assertEquals(400, denied(() -> f.circleService.changeRole(circleId, ownerM, owner, CircleRole.ADMIN)).getStatus().value());
        MemberResponse r = f.circleService.changeRole(circleId, memberM, owner, CircleRole.ADMIN);
        assertEquals(CircleRole.ADMIN, r.role());
        assertEquals(1, f.activityCount(ActivityType.MEMBER_ROLE_CHANGED));
        assertEquals(CircleRole.MEMBER, f.circleService.changeRole(circleId, memberM, owner, CircleRole.MEMBER).role(), "a demotion is allowed");
    }

    @Test
    void circleRolesIncludeStokvelGovernanceRoles() {
        assertEquals(
                List.of(
                        CircleRole.OWNER,
                        CircleRole.ADMIN,
                        CircleRole.MEMBER,
                        CircleRole.TREASURER,
                        CircleRole.CHAIRPERSON
                ),
                List.of(CircleRole.values()),
                "Stokvel governance roles are supported"
        );
    }
}
