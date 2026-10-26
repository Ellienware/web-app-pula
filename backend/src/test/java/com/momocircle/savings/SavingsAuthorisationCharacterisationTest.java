package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.RecurringStatus;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.dto.ContributionDtos.*;
import com.momocircle.dto.ExpenseDtos.*;
import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.GoalDtos.GoalResponse;
import com.momocircle.dto.InvitationDtos.CreateInvitationRequest;
import com.momocircle.dto.InvitationDtos.InvitationResponse;
import com.momocircle.dto.RecurringDtos.CreateRecurringRequest;
import com.momocircle.dto.RecurringDtos.RecurringPlanResponse;
import com.momocircle.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 2A: authorisation and IDOR baseline. Two unrelated circles, A and B. Anyone who belongs only to A must
 * get nothing from B, and resource ids belonging to B must be useless when presented against A.
 */
class SavingsAuthorisationCharacterisationTest {

    private SavingsFixture f;
    private User ownerA, adminA, memberA, ownerB, memberB, nobody;
    private String a, b;
    private GoalResponse goalB;
    private PaymentRequestResponse requestB;
    private RecurringPlanResponse planB;
    private ExpenseResponse expenseB;
    private InvitationResponse inviteB;
    private String dueB, membershipB;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        ownerA = f.user("OwnerA"); adminA = f.user("AdminA"); memberA = f.user("MemberA");
        ownerB = f.user("OwnerB"); memberB = f.user("MemberB"); nobody = f.user("Nobody");
        a = f.newCircle(ownerA, "A", CircleType.SAVINGS_GOAL, d("100")).id();
        b = f.newCircle(ownerB, "B", CircleType.SAVINGS_GOAL, d("100")).id();
        f.join(a, adminA, CircleRole.ADMIN); f.join(a, memberA, CircleRole.MEMBER);
        membershipB = f.join(b, memberB, CircleRole.MEMBER).getId();
        goalB = f.goalService.create(b, ownerB, new CreateGoalRequest("B goal", null, d("50"), null));
        requestB = f.contributionService.createPaymentRequest(b, ownerB, new CreatePaymentRequestRequest(d("10"), null, null));
        planB = f.recurringService.create(b, ownerB, new CreateRecurringRequest("B plan", d("10"), RecurringFrequency.MONTHLY, null, null, null, null));
        dueB = f.plans.get(planB.id()).getPeriods().get(0).getDues().get(0).getId();
        expenseB = f.expenseService.create(b, ownerB, new CreateExpenseRequest("B exp", d("10"), SplitType.EQUAL, null, List.of(new ShareInput(ownerB.getId(), null))));
        inviteB = f.invitationService.invite(b, ownerB, new CreateInvitationRequest("someone@example.com", null));
    }

    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    // ------------------------------------------------------------------ non-members and cross-circle reads

    @Test
    void aMemberOfAHasNoReadAccessToAnythingInB() {
        assertEquals(403, status(() -> f.circleService.get(b, memberA)));
        assertEquals(403, status(() -> f.circleService.listMembers(b, memberA)));
        assertEquals(403, status(() -> f.contributionService.list(b, memberA)));
        assertEquals(403, status(() -> f.contributionService.listPaymentRequests(b, memberA)));
        assertEquals(403, status(() -> f.goalService.list(b, memberA)));
        assertEquals(403, status(() -> f.recurringService.list(b, memberA)));
        assertEquals(403, status(() -> f.expenseService.list(b, memberA)));
        assertEquals(403, status(() -> f.invitationService.listForCircle(b, memberA)));
        assertEquals(403, status(() -> f.activityService.list(b, memberA)));
    }

    @Test
    void aStrangerWithNoCirclesGetsNothingAnywhere() {
        for (String c : new String[] {a, b}) {
            assertEquals(403, status(() -> f.circleService.get(c, nobody)));
            assertEquals(403, status(() -> f.contributionService.contribute(c, nobody, new ContributeRequest(d("1"), null, null, null))));
            assertEquals(403, status(() -> f.goalService.list(c, nobody)));
        }
        assertTrue(f.contributions.isEmpty());
        assertEquals(0, f.circleService.listForUser(nobody).size());
    }

    // ------------------------------------------------------------------ cross-circle writes

    @Test
    void anAdminOfAHasNoAdminPowerInB() {
        assertEquals(403, status(() -> f.goalService.create(b, adminA, new CreateGoalRequest("x", null, d("5"), null))));
        assertEquals(403, status(() -> f.contributionService.createPaymentRequest(b, adminA, new CreatePaymentRequestRequest(d("5"), null, null))));
        assertEquals(403, status(() -> f.invitationService.invite(b, adminA, new CreateInvitationRequest("x@example.com", null))));
        assertEquals(403, status(() -> f.circleService.removeMember(b, membershipB, adminA)));
        assertEquals(403, status(() -> f.circleService.changeRole(b, membershipB, adminA, CircleRole.ADMIN)));
        assertEquals(403, status(() -> f.recurringService.create(b, adminA, new CreateRecurringRequest("x", d("5"), RecurringFrequency.WEEKLY, null, null, null, null))));
    }

    @Test
    void anAdminCannotReachBsResourcesByPresentingTheirIdsUnderCircleA() {
        // Each of these runs as a real admin of A, in circle A, with an id that belongs to circle B.
        assertEquals(400, status(() -> f.goalService.close(a, goalB.id(), adminA)));
        assertEquals(400, status(() -> f.contributionService.cancelPaymentRequest(a, requestB.id(), adminA)));
        assertEquals(400, status(() -> f.contributionService.remindPaymentRequest(a, requestB.id(), adminA)));
        assertEquals(400, status(() -> f.recurringService.generateNextPeriod(a, planB.id(), adminA)));
        assertEquals(400, status(() -> f.recurringService.setStatus(a, planB.id(), adminA, RecurringStatus.CANCELLED)));
        assertEquals(400, status(() -> f.invitationService.cancel(a, inviteB.id(), adminA)));
        assertEquals(400, status(() -> f.circleService.removeMember(a, membershipB, adminA)));
        assertEquals(400, status(() -> f.circleService.changeRole(a, membershipB, ownerA, CircleRole.ADMIN)));
        assertEquals(400, status(() -> f.expenseService.cancel(a, expenseB.id(), adminA, null)));
        assertEquals(403, status(() -> f.expenseService.markReceivedExternally(expenseB.shares().get(0).id(), adminA)), "only the payer can record repayment");
        assertEquals(400, status(() -> f.recurringService.payDue(a, dueB, memberA)));
        assertEquals(RecurringStatus.ACTIVE, f.plans.get(planB.id()).getStatus(), "B's plan was not touched");
        assertTrue(f.membershipOf(b, memberB).isPresent(), "B's member was not removed");
    }

    @Test
    void aContributionToAcannotBeEarmarkedForBsGoalOrBsPaymentRequest() {
        assertEquals(400, status(() -> f.contributionService.contribute(a, memberA, new ContributeRequest(d("5"), null, goalB.id(), null))));
        assertEquals(400, status(() -> f.contributionService.contribute(a, memberA, new ContributeRequest(d("5"), requestB.id(), null, null))));
        assertTrue(f.contributions.isEmpty());
    }

    @Test
    void FINDING_theErrorCodeRevealsWhetherAnIdExistsInAnotherCircle() {
        // 400 "does not belong to this circle" for a real id in circle B, versus 404 for an id that exists nowhere.
        assertEquals(400, status(() -> f.goalService.close(a, goalB.id(), adminA)));
        assertEquals(404, status(() -> f.goalService.close(a, "no-such-goal", adminA)));
    }

    @Test
    void invitationsAreBoundToTheInvitedEmailNotJustTheToken() {
        assertEquals(403, status(() -> f.invitationService.accept(inviteB.token(), memberA)));
        assertFalse(f.membershipOf(b, memberA).isPresent());
    }

    // ------------------------------------------------------------------ role boundaries inside one circle

    @Test
    void rolesAreEnforcedInsideACircle() {
        assertEquals(403, status(() -> f.goalService.create(a, memberA, new CreateGoalRequest("x", null, d("5"), null))));
        assertEquals(403, status(() -> f.circleService.changeRole(a, f.membershipOf(a, memberA).orElseThrow().getId(), adminA, CircleRole.ADMIN)));
        assertEquals(403, status(() -> f.circleService.removeMember(a, f.membershipOf(a, adminA).orElseThrow().getId(), memberA)));
        f.goalService.create(a, adminA, new CreateGoalRequest("ok", null, d("5"), null));
    }

    @Test
    void aRemovedMemberImmediatelyLosesEveryAccessButTheirPastContributionsRemain() {
        f.contributionService.contribute(a, memberA, new ContributeRequest(d("5"), null, null, null));
        f.circleService.removeMember(a, f.membershipOf(a, memberA).orElseThrow().getId(), adminA);
        assertEquals(403, status(() -> f.circleService.get(a, memberA)));
        assertEquals(403, status(() -> f.contributionService.contribute(a, memberA, new ContributeRequest(d("5"), null, null, null))));
        assertEquals(1, f.contributionService.list(a, ownerA).size(), "history is not erased by removal");
    }
}
