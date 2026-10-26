package com.momocircle.future;

import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.future.FutureDtos.ReminderSetting;
import com.momocircle.future.FutureDtos.ReminderSettings;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.service.NotificationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in reminders. A reminder is ONLY an in-app notification: this class has no payment, wallet, ledger, contribution or
 * payout dependency, and no code path here can create any of them. No preference row means OFF.
 */
@Service
public class FutureReminderService {

    private final ReminderPreferenceRepository prefs;
    private final ReminderLogRepository log;
    private final CircleMembershipRepository memberships;
    private final CircleGoalRepository goals;
    private final ContributionRepository contributions;
    private final AllocationPlanRepository plans;
    private final NotificationService notifications;

    public FutureReminderService(ReminderPreferenceRepository prefs, ReminderLogRepository log,
                                 CircleMembershipRepository memberships, CircleGoalRepository goals,
                                 ContributionRepository contributions, AllocationPlanRepository plans,
                                 NotificationService notifications) {
        this.prefs = prefs; this.log = log; this.memberships = memberships; this.goals = goals;
        this.contributions = contributions; this.plans = plans; this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public ReminderSettings settings(User user) {
        Set<ReminderKind> on = enabledKinds(user.getId());
        List<ReminderSetting> out = new ArrayList<>();
        for (ReminderKind k : ReminderKind.values()) out.add(new ReminderSetting(k, on.contains(k), k.description()));
        return new ReminderSettings(out, List.of(
                "Reminders are off until you turn them on and only appear as in-app notifications.",
                "A reminder never transfers, schedules or contributes money.",
                "Stokvel contribution reminders already come from the stokvel itself and are not duplicated here."));
    }

    @Transactional
    public ReminderSettings update(User user, ReminderKind kind, boolean enabled) {
        ReminderPreference p = prefs.findByUserIdAndKind(user.getId(), kind).orElseGet(() -> {
            ReminderPreference n = new ReminderPreference(); n.setUserId(user.getId()); n.setKind(kind); return n;
        });
        p.setEnabled(enabled);
        prefs.save(p);
        return settings(user);
    }

    public List<String> usersWithReminders() { return prefs.findUserIdsWithAnyEnabled(); }

    /** Sends every reminder that is due today for this user and has not been sent before. Returns how many were sent. */
    @Transactional
    public int runForUser(User user, LocalDate today) {
        Set<ReminderKind> on = enabledKinds(user.getId());
        if (on.isEmpty()) return 0;
        int sent = 0;
        List<String> circleIds = memberships.findByUserId(user.getId()).stream().map(m -> m.getCircle().getId()).toList();
        List<CircleGoal> myGoals = circleIds.isEmpty() ? List.of() : goals.findByCircleIdInAndStatus(circleIds, GoalStatus.ACTIVE);

        if (on.contains(ReminderKind.GOAL_DEADLINE)) {
            for (CircleGoal g : myGoals) {
                for (String key : ReminderPlanner.goalDeadlineKeys(g.getId(), g.getDeadline(), today)) {
                    sent += send(user, key, "Reminder: your goal \"" + g.getTitle() + "\" has a target date of " + g.getDeadline()
                            + ". This is a reminder only; nothing is scheduled or transferred.", g.getId());
                }
            }
        }
        if (on.contains(ReminderKind.SAVE_TO_BUY_PROGRESS)) {
            for (CircleGoal g : myGoals) {
                if (g.getPurchaseVertical() == null) continue;
                BigDecimal recorded = contributions.sumConfirmedAmountForGoal(g.getId(), PaymentStatus.SUCCESSFUL);
                int bucket = ReminderPlanner.progressBucket(minor(recorded), minor(g.getTargetAmount()));
                if (bucket > 0) {
                    sent += send(user, ReminderPlanner.saveToBuyKey(g.getId(), bucket),
                            "Save-to-Buy: \"" + g.getTitle() + "\" has passed " + bucket + "% of its recorded target. "
                                    + "Nothing is purchased automatically; you confirm any purchase yourself.", g.getId());
                }
            }
        }
        if (on.contains(ReminderKind.PLAN_REVIEW)
                && !plans.findByOwnerUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), PlanStatus.ARCHIVED, PageRequest.of(0, 1)).isEmpty()) {
            sent += send(user, ReminderPlanner.planReviewKey(today),
                    "Monthly check-in: take a moment to review your PULA Future allocation plans. They are planning estimates only.", null);
        }
        return sent;
    }

    private int send(User user, String key, String message, String referenceId) {
        if (log.existsByUserIdAndReminderKey(user.getId(), key)) return 0;
        ReminderLog l = new ReminderLog(); l.setUserId(user.getId()); l.setReminderKey(key);
        log.save(l);
        notifications.notify(user, NotificationType.FUTURE_REMINDER, message, referenceId);
        return 1;
    }

    private Set<ReminderKind> enabledKinds(String userId) {
        Set<ReminderKind> on = EnumSet.noneOf(ReminderKind.class);
        for (ReminderPreference p : prefs.findByUserId(userId)) if (p.isEnabled()) on.add(p.getKind());
        return on;
    }

    private static long minor(BigDecimal v) {
        return v == null ? 0L : v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }
}
