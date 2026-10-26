package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.dto.ContributionDtos.ContributeRequest;
import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.GoalDtos.GoalResponse;
import com.momocircle.exception.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE 2A: current goal behaviour. Goals are earmarks on contributions; progress is computed on read. */
class GoalCharacterisationTest {

    private SavingsFixture f;
    private User owner, admin, member, broke, outsider;
    private String circleId;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); admin = f.user("Admin"); member = f.user("Member"); broke = f.user("Broke", "0000"); outsider = f.user("Outsider");
        circleId = f.newCircle(owner, "Savers", CircleType.SAVINGS_GOAL, null).id();
        f.join(circleId, admin, CircleRole.ADMIN);
        f.join(circleId, member, CircleRole.MEMBER);
        f.join(circleId, broke, CircleRole.MEMBER);
    }

    private GoalResponse goal(User actor, String target) {
        return f.goalService.create(circleId, actor, new CreateGoalRequest("  Taxi fund ", "desc", d(target), LocalDate.now().plusMonths(3)));
    }

    private void contribute(User who, String amount, String goalId) {
        f.contributionService.contribute(circleId, who, new ContributeRequest(d(amount), null, goalId, null));
    }

    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    @Test
    void ownersAndAdminsCreateActiveGoalsWithTargetDeadlineAndCreator() {
        GoalResponse g = goal(admin, "500.00");
        assertEquals("Taxi fund", g.title(), "trimmed");
        assertEquals(GoalStatus.ACTIVE, g.status());
        assertEquals(d("500.00"), g.targetAmount());
        assertEquals(LocalDate.now().plusMonths(3), g.deadline());
        assertEquals("Admin", g.createdByName());
        assertEquals(0, g.progressPercent());
        assertEquals(1, f.activityCount(ActivityType.GOAL_CREATED));
        goal(owner, "10.00");
        assertEquals(2, f.goals.size());
    }

    @Test
    void membersAndNonMembersCannotCreateGoalsAndTheTargetMustBeValid() {
        assertEquals(403, status(() -> goal(member, "500.00")));
        assertEquals(403, status(() -> goal(outsider, "500.00")));
        for (String bad : new String[] {"0", "-1", "10.005"}) assertEquals(400, status(() -> goal(admin, bad)), bad);
    }

    @Test
    void aDeadlineInThePastIsAccepted() {
        GoalResponse g = f.goalService.create(circleId, admin, new CreateGoalRequest("Old", null, d("10"), LocalDate.now().minusDays(10)));
        assertEquals(GoalStatus.ACTIVE, g.status());
    }

    @Test
    void onlyContributionsEarmarkedForTheGoalCountTowardIt() {
        GoalResponse g = goal(admin, "500.00");
        contribute(member, "100.00", g.id());
        contribute(member, "400.00", null); // counts for the circle, not for the goal
        GoalResponse now = f.goalService.list(circleId, member).get(0);
        assertEquals(d("100.00"), now.raisedAmount());
        assertEquals(20, now.progressPercent());
        assertEquals(GoalStatus.ACTIVE, now.status());
    }

    @Test
    void failedPaymentsDoNotCountTowardAGoal() {
        GoalResponse g = goal(admin, "500.00");
        contribute(broke, "500.00", g.id());
        assertEquals(0, f.goalService.list(circleId, member).get(0).progressPercent());
        assertEquals(GoalStatus.ACTIVE, f.goals.get(g.id()).getStatus());
    }

    @Test
    void progressPercentIsFlooredAndCappedAtOneHundred() {
        GoalResponse g = goal(admin, "1000.00");
        contribute(member, "999.99", g.id());
        assertEquals(99, f.goalService.list(circleId, member).get(0).progressPercent());
        contribute(member, "500.00", g.id());
        assertEquals(100, f.goalService.list(circleId, member).get(0).progressPercent());
    }

    @Test
    void reachingTheTargetMarksTheGoalReachedOnceAndRecordsActivityButNotifiesNobody() {
        GoalResponse g = goal(admin, "300.00");
        contribute(member, "100.00", g.id());
        assertEquals(GoalStatus.ACTIVE, f.goals.get(g.id()).getStatus());
        contribute(admin, "200.00", g.id());
        assertEquals(GoalStatus.REACHED, f.goals.get(g.id()).getStatus());
        assertEquals(1, f.activityCount(ActivityType.GOAL_REACHED));
        contribute(member, "50.00", g.id()); // contributions are still accepted afterwards
        assertEquals(1, f.activityCount(ActivityType.GOAL_REACHED), "only recorded once");
        assertEquals(d("350.00"), f.goalService.list(circleId, member).get(0).raisedAmount());
        // GoalService has no notification dependency at all: reaching a goal sends no notification today.
    }

    @Test
    void anAdminCanCloseAGoalAndAClosedGoalNeverBecomesReached() {
        GoalResponse g = goal(admin, "100.00");
        assertEquals(403, status(() -> f.goalService.close(circleId, g.id(), member)));
        assertEquals(GoalStatus.CLOSED, f.goalService.close(circleId, g.id(), admin).status());
        contribute(member, "100.00", g.id()); // still accepted
        assertEquals(GoalStatus.CLOSED, f.goals.get(g.id()).getStatus());
        assertEquals(0, f.activityCount(ActivityType.GOAL_REACHED));
    }

    @Test
    void closingHasNoStateGuardAReachedOrClosedGoalCanBeClosedAgain() {
        GoalResponse g = goal(admin, "10.00");
        contribute(member, "10.00", g.id());
        assertEquals(GoalStatus.REACHED, f.goals.get(g.id()).getStatus());
        f.goalService.close(circleId, g.id(), admin);
        f.goalService.close(circleId, g.id(), admin);
        assertEquals(2, f.activityCount(ActivityType.GOAL_CLOSED), "no guard: each close is recorded");
    }

    @Test
    void goalsAreListedNewestFirstForMembersOnly() {
        goal(admin, "10.00");
        f.goalService.create(circleId, admin, new CreateGoalRequest("Second", null, d("20.00"), null));
        assertEquals("Second", f.goalService.list(circleId, member).get(0).title());
        assertEquals(403, status(() -> f.goalService.list(circleId, outsider)));
    }
}
