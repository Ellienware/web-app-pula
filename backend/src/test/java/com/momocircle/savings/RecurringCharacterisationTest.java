package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.RecurringDue;
import com.momocircle.domain.RecurringPlan;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.RecurringDueStatus;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.RecurringStatus;
import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.RecurringDtos.CreateRecurringRequest;
import com.momocircle.dto.RecurringDtos.RecurringPlanResponse;
import com.momocircle.exception.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 2A: current recurring-contribution behaviour. A plan opens "periods"; each period holds one "due" per
 * member that existed when the period was generated. The scheduler (generateDuePeriods) is invoked directly.
 */
class RecurringCharacterisationTest {

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

    private RecurringPlanResponse plan(User actor, String amount, RecurringFrequency freq, LocalDate start, Boolean first, String goalId) {
        return f.recurringService.create(circleId, actor, new CreateRecurringRequest("Monthly stokvel", d(amount), freq, start, goalId, "note", first));
    }

    private RecurringPlan entity(String id) { return f.plans.get(id); }

    private List<RecurringDue> duesOf(String planId) {
        return entity(planId).getPeriods().stream().flatMap(p -> p.getDues().stream()).toList();
    }

    private RecurringDue dueOf(String planId, User u) {
        return duesOf(planId).stream().filter(x -> x.getMember().getId().equals(u.getId())).findFirst().orElseThrow();
    }

    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    // ------------------------------------------------------------------ creation

    @Test
    void anAdminCreatesAPlanAndTheFirstPeriodOpensWithOnePendingDuePerMember() {
        LocalDate start = LocalDate.of(2026, 3, 1);
        RecurringPlanResponse p = plan(admin, "500.00", RecurringFrequency.MONTHLY, start, null, null);
        assertEquals(d("500.00"), p.amountPerMember());
        assertEquals(RecurringFrequency.MONTHLY, p.frequency());
        assertEquals(RecurringStatus.ACTIVE, p.status());
        assertEquals(1, p.periods().size());
        assertEquals("March 2026", p.periods().get(0).label());
        assertEquals(start, p.periods().get(0).dueDate());
        assertEquals(LocalDate.of(2026, 4, 1), p.nextDueDate(), "the next due date has already advanced one month");
        assertEquals(4, p.periods().get(0).dues().size(), "owner, admin and two members");
        assertTrue(duesOf(p.id()).stream().allMatch(x -> x.getStatus() == RecurringDueStatus.PENDING && x.getAmount().compareTo(d("500.00")) == 0));
        assertEquals(1, f.activityCount(ActivityType.RECURRING_CREATED));
        assertEquals(1, f.activityCount(ActivityType.RECURRING_EXECUTED));
    }

    @Test
    void everyFrequencyAdvancesTheNextDueDateByItsOwnInterval() {
        LocalDate s = LocalDate.of(2026, 1, 31);
        assertEquals(s.plusWeeks(1), plan(admin, "1", RecurringFrequency.WEEKLY, s, null, null).nextDueDate());
        assertEquals(s.plusWeeks(2), plan(admin, "1", RecurringFrequency.BIWEEKLY, s, null, null).nextDueDate());
        assertEquals(LocalDate.of(2026, 2, 28), plan(admin, "1", RecurringFrequency.MONTHLY, s, null, null).nextDueDate(), "31 Jan + 1 month clamps to 28 Feb");
        assertEquals(s.plusMonths(3), plan(admin, "1", RecurringFrequency.QUARTERLY, s, null, null).nextDueDate());
        assertEquals(s.plusYears(1), plan(admin, "1", RecurringFrequency.YEARLY, s, null, null).nextDueDate());
    }

    @Test
    void theStartDateDefaultsToTodayAndTheFirstPeriodCanBeSkipped() {
        RecurringPlanResponse p = plan(admin, "10", RecurringFrequency.WEEKLY, null, false, null);
        assertEquals(0, p.periods().size());
        assertEquals(LocalDate.now(), p.nextDueDate(), "no period opened: the next due date stays at the start date");
        assertEquals(0, f.activityCount(ActivityType.RECURRING_EXECUTED));
    }

    @Test
    void creationIsAdminOnlyAndTheAmountMustBeValid() {
        assertEquals(403, status(() -> plan(member, "10", RecurringFrequency.WEEKLY, null, null, null)));
        assertEquals(403, status(() -> plan(outsider, "10", RecurringFrequency.WEEKLY, null, null, null)));
        for (String bad : new String[] {"0", "-1", "10.005"}) assertEquals(400, status(() -> plan(admin, bad, RecurringFrequency.WEEKLY, null, null, null)), bad);
    }

    @Test
    void aPlanCanBeTiedToAGoalOfTheSameCircleOnly() {
        var goal = f.goalService.create(circleId, admin, new CreateGoalRequest("G", null, d("1000"), null));
        RecurringPlanResponse p = plan(admin, "10", RecurringFrequency.WEEKLY, null, false, goal.id());
        assertEquals(goal.id(), p.goalId());
        assertEquals(404, status(() -> plan(admin, "10", RecurringFrequency.WEEKLY, null, false, "no-goal")));
        String other = f.newCircle(owner, "Other", CircleType.SAVINGS_GOAL, null).id();
        var foreign = f.goalService.create(other, owner, new CreateGoalRequest("Foreign", null, d("5"), null));
        assertEquals(400, status(() -> plan(admin, "10", RecurringFrequency.WEEKLY, null, false, foreign.id())));
    }

    // ------------------------------------------------------------------ paying a due

    @Test
    void aMemberPaysOwnDueWhichBecomesPaidCountsAsAContributionAndIsNotChargedTwice() {
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, null);
        RecurringDue due = dueOf(p.id(), member);
        f.recurringService.payDue(circleId, due.getId(), member);
        assertEquals(RecurringDueStatus.PAID, due.getStatus());
        assertNotNull(due.getPaidAt());
        assertEquals(PaymentStatus.SUCCESSFUL, due.getPayment().getStatus());
        assertEquals(1, f.contributions.size());
        assertEquals(1, f.activityCount(ActivityType.CONTRIBUTION_MADE));
        f.recurringService.payDue(circleId, due.getId(), member); // paying a PAID due is a no-op
        assertEquals(1, f.contributions.size());
        assertEquals(1, f.payments.size());
    }

    @Test
    void aDueCanOnlyBePaidByItsOwnMemberAndOnlyInItsOwnCircle() {
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, null);
        RecurringDue due = dueOf(p.id(), member);
        assertEquals(403, status(() -> f.recurringService.payDue(circleId, due.getId(), admin)), "not even an admin can pay someone else's due");
        assertEquals(403, status(() -> f.recurringService.payDue(circleId, due.getId(), outsider)));
        assertEquals(404, status(() -> f.recurringService.payDue(circleId, "no-such-due", member)));
        String other = f.newCircle(member, "Other", CircleType.SAVINGS_GOAL, null).id();
        assertEquals(400, status(() -> f.recurringService.payDue(other, due.getId(), member)), "due id from a different circle");
    }

    @Test
    void aFailedPaymentLeavesTheDuePendingAndTheRetryReusesTheSameIdempotencyKey() {
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, null);
        RecurringDue due = dueOf(p.id(), broke);
        assertEquals(400, status(() -> f.recurringService.payDue(circleId, due.getId(), broke)));
        assertEquals(RecurringDueStatus.PENDING, due.getStatus());
        assertNull(due.getPayment());
        // FINDING (logic level): the key is "recurring-due-<dueId>", so a retry meets the first (failed) payment
        // again instead of making a fresh attempt. How this behaves under real transaction rollback is NOT VERIFIED.
        assertEquals(400, status(() -> f.recurringService.payDue(circleId, due.getId(), broke)));
        assertEquals(1, f.payments.size(), "no second payment was ever attempted");
    }

    @Test
    void aMissedDueCanStillBePaidLaterAndBecomesPaid() {
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, null);
        RecurringDue due = dueOf(p.id(), member);
        due.setStatus(RecurringDueStatus.MISSED);
        f.recurringService.payDue(circleId, due.getId(), member);
        assertEquals(RecurringDueStatus.PAID, due.getStatus());
    }

    @Test
    void paidDuesFeedTheCircleTotalAndAGoalsProgress() {
        var goal = f.goalService.create(circleId, admin, new CreateGoalRequest("G", null, d("400.00"), null));
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, goal.id());
        f.recurringService.payDue(circleId, dueOf(p.id(), member).getId(), member);
        f.recurringService.payDue(circleId, dueOf(p.id(), admin).getId(), admin);
        assertEquals(50, f.goalService.list(circleId, owner).get(0).progressPercent());
        assertEquals(d("200.00"), f.recurringService.list(circleId, owner).get(0).totalCollected());
    }

    // ------------------------------------------------------------------ due dates, overdue and missed

    @Test
    void FINDING_anOverdueDueIsOnlyMarkedMissedWhenTheNextPeriodIsGenerated() {
        LocalDate start = LocalDate.now().minusDays(10); // first due 10 days ago; next due ~20 days from now
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, start, null, null);
        f.recurringService.generateDuePeriods();
        assertTrue(duesOf(p.id()).stream().allMatch(x -> x.getStatus() == RecurringDueStatus.PENDING),
                "FINDING: 10 days overdue, but still PENDING because the plan is not yet due to generate a period");
        assertEquals(1, entity(p.id()).getPeriods().size());
    }

    @Test
    void whenThePlanIsDueTheSchedulerMarksUnpaidOverdueDuesMissedKeepsPaidOnesAndOpensTheNextPeriod() {
        LocalDate start = LocalDate.now().minusMonths(1).minusDays(5); // next due date is already in the past
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, start, null, null);
        f.recurringService.payDue(circleId, dueOf(p.id(), member).getId(), member);
        f.recurringService.generateDuePeriods();
        assertEquals(RecurringDueStatus.PAID, dueOf(p.id(), member).getStatus());
        assertEquals(RecurringDueStatus.MISSED, dueOf(p.id(), admin).getStatus());
        assertEquals(RecurringDueStatus.MISSED, dueOf(p.id(), broke).getStatus());
        assertEquals(100, dueOf(p.id(), admin).getAmount().intValue(), "the missed amount is kept, not erased");
        assertEquals(2, entity(p.id()).getPeriods().size());
        assertEquals(start.plusMonths(2), entity(p.id()).getNextDueDate());
        assertEquals(2, f.activityCount(ActivityType.RECURRING_EXECUTED));
        long pendingNewDues = entity(p.id()).getPeriods().get(1).getDues().stream().filter(x -> x.getStatus() == RecurringDueStatus.PENDING).count();
        assertEquals(4, pendingNewDues);
        assertEquals(1, f.activityCount(ActivityType.CONTRIBUTION_MADE), "only the one real payment is recorded; marking dues missed adds no activity of its own");
    }

    @Test
    void pausedAndCancelledPlansAreIgnoredByTheScheduler() {
        LocalDate start = LocalDate.now().minusMonths(2);
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, start, null, null);
        f.recurringService.setStatus(circleId, p.id(), admin, RecurringStatus.PAUSED);
        f.recurringService.generateDuePeriods();
        assertEquals(1, entity(p.id()).getPeriods().size());
        assertEquals(400, status(() -> f.recurringService.generateNextPeriod(circleId, p.id(), admin)), "only active plans open periods");
    }

    @Test
    void membersWhoJoinLaterHaveNoDueInPeriodsAlreadyOpenButGetOneInTheNextPeriod() {
        RecurringPlanResponse p = plan(admin, "100.00", RecurringFrequency.MONTHLY, LocalDate.now(), null, null);
        User late = f.user("Late");
        f.join(circleId, late, CircleRole.MEMBER);
        assertEquals(4, entity(p.id()).getPeriods().get(0).getDues().size());
        f.recurringService.generateNextPeriod(circleId, p.id(), admin);
        assertEquals(5, entity(p.id()).getPeriods().get(1).getDues().size());
    }

    // ------------------------------------------------------------------ status changes and listing

    @Test
    void setStatusIsAdminOnlyRecordsCancellationAndAllowsReactivatingACancelledPlan() {
        RecurringPlanResponse p = plan(admin, "10", RecurringFrequency.WEEKLY, null, false, null);
        assertEquals(403, status(() -> f.recurringService.setStatus(circleId, p.id(), member, RecurringStatus.PAUSED)));
        f.recurringService.setStatus(circleId, p.id(), admin, RecurringStatus.CANCELLED);
        assertEquals(1, f.activityCount(ActivityType.RECURRING_CANCELLED));
        assertEquals(RecurringStatus.ACTIVE, f.recurringService.setStatus(circleId, p.id(), admin, RecurringStatus.ACTIVE).status(),
                "no transition guard: a cancelled plan can be reactivated");
    }

    @Test
    void plansBelongToTheirCircleAndEveryMemberSeesEveryonesDues() {
        RecurringPlanResponse p = plan(admin, "10", RecurringFrequency.WEEKLY, null, null, null);
        String other = f.newCircle(owner, "Other", CircleType.SAVINGS_GOAL, null).id();
        assertEquals(400, status(() -> f.recurringService.generateNextPeriod(other, p.id(), owner)), "plan id from another circle");
        assertEquals(400, status(() -> f.recurringService.setStatus(other, p.id(), owner, RecurringStatus.PAUSED)));
        var seenByMember = f.recurringService.list(circleId, member);
        assertEquals(4, seenByMember.get(0).periods().get(0).dues().size(), "a plain member sees all four members' dues");
        assertEquals(403, status(() -> f.recurringService.list(circleId, outsider)));
    }
}
