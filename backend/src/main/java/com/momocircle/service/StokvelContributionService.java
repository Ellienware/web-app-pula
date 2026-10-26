package com.momocircle.service;

import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.Contribution;
import com.momocircle.domain.StokvelContributionLink;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.ContributionDtos.ContributeRequest;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.StokvelContributionLinkRepository;
import com.momocircle.stokvel.ContributionState;
import com.momocircle.stokvel.StokvelSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stokvel contributions REUSE ContributionService (payment, validation, goal earmarking, activity). This class only
 * decides which period a contribution counts toward and computes expected-vs-actual status. Status is computed from
 * confirmed payments each time; nothing here is a second ledger.
 */
@Service
public class StokvelContributionService {

    private static final int MAX_PERIODS_SCANNED = 120;

    private final StokvelSupport support;
    private final ContributionService contributionService;
    private final ContributionRepository contributionRepository;
    private final StokvelContributionLinkRepository links;
    private final GoalService goalService;
    private final NotificationService notificationService;

    public StokvelContributionService(StokvelSupport support, ContributionService contributionService,
                                      ContributionRepository contributionRepository,
                                      StokvelContributionLinkRepository links, GoalService goalService,
                                      NotificationService notificationService) {
        this.support = support;
        this.contributionService = contributionService;
        this.contributionRepository = contributionRepository;
        this.links = links;
        this.goalService = goalService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ContributionResponse contribute(String circleId, User user, StokvelContributeRequest req) {
        var ctx = support.require(circleId, user);
        StokvelProfile p = ctx.profile();
        LocalDate today = support.today();
        int current = StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(), today);
        if (current < 0) {
            throw ApiException.badRequest("This stokvel has not started yet");
        }
        // Period is chosen by the server (oldest unpaid period first), never by the client.
        int period = allocatePeriod(ctx.membership(), p, user.getId(), current, today);

        CircleGoal goal = p.getGoal();
        GoalStatus before = goal == null ? null : goal.getStatus();
        ContributionResponse resp = contributionService.contribute(circleId, user,
                new ContributeRequest(req.amount(), null, goal == null ? null : goal.getId(), req.idempotencyKey()));

        Contribution contribution = contributionRepository.findById(resp.id()).orElseThrow();
        if (links.findByContributionId(contribution.getId()).isEmpty()) {
            StokvelContributionLink link = new StokvelContributionLink();
            link.setCircle(ctx.circle());
            link.setUser(user);
            link.setContribution(contribution);
            link.setPeriodIndex(period);
            links.save(link);
        }
        if (goal != null && before == GoalStatus.ACTIVE) {
            CircleGoal after = goalService.requireGoal(circleId, goal.getId());
            if (after.getStatus() == GoalStatus.REACHED) {
                for (CircleMembership m : support.members(circleId)) {
                    notificationService.notify(m.getUser(), NotificationType.GOAL_COMPLETED,
                            "Goal reached: " + ctx.circle().getName(), circleId);
                }
            }
        }
        return resp;
    }

    @Transactional(readOnly = true)
    public ContributionStatusView status(String circleId, User viewer) {
        var ctx = support.require(circleId, viewer);
        StokvelProfile p = ctx.profile();
        LocalDate today = support.today();
        int current = StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(), today);
        if (current < 0) {
            return new ContributionStatusView(-1, p.getStartDate(), p.getStartDate(), p.getContributionAmount(),
                    List.of(), List.of());
        }
        List<CircleMembership> members = support.members(circleId);
        Map<String, BigDecimal> confirmed = confirmedMap(circleId);
        List<MemberPeriodStatus> now = new ArrayList<>();
        List<MemberPeriodStatus> missed = new ArrayList<>();
        for (CircleMembership m : members) {
            now.add(memberStatus(p, m, current, confirmed, today));
            int first = firstOwedPeriod(p, m);
            for (int k = Math.max(first, current - MAX_PERIODS_SCANNED); k < current; k++) {
                MemberPeriodStatus s = memberStatus(p, m, k, confirmed, today);
                if (s.status() == ContributionStatus.MISSED) {
                    missed.add(s);
                }
            }
        }
        return new ContributionStatusView(current,
                StokvelSchedule.periodStart(p.getStartDate(), p.getContributionFrequency(), current),
                StokvelSchedule.periodEndExclusive(p.getStartDate(), p.getContributionFrequency(), current).minusDays(1),
                p.getContributionAmount(), now, missed);
    }

    // ---- shared helpers (also used by rotation, notices and insights) ----

    Map<String, BigDecimal> confirmedMap(String circleId) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] row : links.confirmedByMemberAndPeriod(circleId, PaymentStatus.SUCCESSFUL)) {
            map.put(row[0] + "|" + row[1], (BigDecimal) row[2]);
        }
        return map;
    }

    MemberPeriodStatus memberStatus(StokvelProfile p, CircleMembership m, int period,
                                    Map<String, BigDecimal> confirmed, LocalDate today) {
        BigDecimal expected = p.getContributionAmount();
        BigDecimal paid = confirmed.getOrDefault(m.getUser().getId() + "|" + period, BigDecimal.ZERO);
        boolean ended = StokvelSchedule.hasEnded(p.getStartDate(), p.getContributionFrequency(), period, today);
        ContributionState st = ContributionState.of(expected, paid, ended);
        return new MemberPeriodStatus(m.getUser().getId(), m.getUser().getFullName(), period,
                expected == null ? BigDecimal.ZERO : expected, paid, ContributionStatus.valueOf(st.name()));
    }

    /** A member owes the period that contains the day they joined, and every later one. */
    int firstOwedPeriod(StokvelProfile p, CircleMembership m) {
        LocalDate joined = m.getCreatedAt().atZone(StokvelSupport.ZONE).toLocalDate();
        return Math.max(0, StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(), joined));
    }

    private int allocatePeriod(CircleMembership m, StokvelProfile p, String userId, int current, LocalDate today) {
        if (p.getContributionAmount() == null) {
            return current;
        }
        Map<String, BigDecimal> confirmed = confirmedMap(m.getCircle().getId());
        for (int k = Math.max(firstOwedPeriod(p, m), current - MAX_PERIODS_SCANNED); k <= current; k++) {
            BigDecimal paid = confirmed.getOrDefault(userId + "|" + k, BigDecimal.ZERO);
            if (paid.compareTo(p.getContributionAmount()) < 0) {
                return k;
            }
        }
        return current;
    }
}
