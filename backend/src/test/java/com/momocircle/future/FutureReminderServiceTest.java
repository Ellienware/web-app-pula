package com.momocircle.future;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FutureReminderServiceTest {
    @Mock ReminderPreferenceRepository prefs; @Mock ReminderLogRepository log; @Mock CircleMembershipRepository memberships;
    @Mock CircleGoalRepository goals; @Mock ContributionRepository contributions; @Mock AllocationPlanRepository plans;
    @Mock NotificationService notifications;
    FutureReminderService service; User alice;

    @BeforeEach void setUp() {
        service = new FutureReminderService(prefs, log, memberships, goals, contributions, plans, notifications);
        alice = new User(); alice.setId("alice");
        Circle c = new Circle(); c.setId("c1");
        CircleMembership m = new CircleMembership(); m.setCircle(c);
        when(memberships.findByUserId("alice")).thenReturn(List.of(m));
        CircleGoal g = new CircleGoal(); g.setId("g1"); g.setCircle(c); g.setTitle("Laptop"); g.setStatus(GoalStatus.ACTIVE);
        g.setTargetAmount(new BigDecimal("1000")); g.setDeadline(LocalDate.of(2026, 11, 30));
        when(goals.findByCircleIdInAndStatus(any(), eq(GoalStatus.ACTIVE))).thenReturn(List.of(g));
    }
    private void enable(ReminderKind k) {
        ReminderPreference p = new ReminderPreference(); p.setUserId("alice"); p.setKind(k); p.setEnabled(true);
        when(prefs.findByUserId("alice")).thenReturn(List.of(p));
    }

    @Test void nothingIsSentUnlessTheUserOptedIn() {
        when(prefs.findByUserId("alice")).thenReturn(List.of());
        assertEquals(0, service.runForUser(alice, LocalDate.of(2026, 11, 25)));
        verifyNoInteractions(notifications);
    }
    @Test void goalDeadlineReminderIsSentOnceAsAnInformationalNotification() {
        enable(ReminderKind.GOAL_DEADLINE);
        when(log.existsByUserIdAndReminderKey(any(), any())).thenReturn(false);
        assertEquals(1, service.runForUser(alice, LocalDate.of(2026, 11, 25)));
        verify(notifications).notify(eq(alice), eq(NotificationType.FUTURE_REMINDER), contains("reminder only"), eq("g1"));
        when(log.existsByUserIdAndReminderKey("alice", "GOAL_DEADLINE:g1:2026-11-30:7")).thenReturn(true);
        clearInvocations(notifications);
        assertEquals(0, service.runForUser(alice, LocalDate.of(2026, 11, 26)));
        verifyNoInteractions(notifications);
    }
    @Test void disabledKindsDoNotFire() {
        enable(ReminderKind.PLAN_REVIEW);                       // goal reminders NOT enabled
        when(plans.findByOwnerUserIdAndStatusNotOrderByCreatedAtDesc(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());
        assertEquals(0, service.runForUser(alice, LocalDate.of(2026, 11, 25)));
        verifyNoInteractions(notifications);
    }
    /** Structural guard: reminders can only notify. */
    @Test void reminderServiceHasNoMoneyMovingDependencies() {
        for (Class<?> t : FutureReminderService.class.getConstructors()[0].getParameterTypes()) {
            String n = t.getName().toLowerCase();
            assertFalse(n.contains("wallet") || n.contains("ledger") || n.contains("payment") || n.contains("payout")
                    || n.contains("settlement") || n.endsWith("contributionservice"), "Forbidden dependency: " + t.getName());
        }
    }
}
