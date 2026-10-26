package com.momocircle.service;

import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelNoticeLog;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.StokvelDtos.ContributionStatus;
import com.momocircle.dto.StokvelDtos.MemberPeriodStatus;
import com.momocircle.repository.StokvelCycleRepository;
import com.momocircle.repository.StokvelNoticeLogRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.repository.StokvelRotationSlotRepository;
import com.momocircle.stokvel.RotationOrder;
import com.momocircle.stokvel.StokvelSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scheduled notices: contribution due, contribution missed, upcoming payout. Each notice is logged once per
 * member/period/kind (stokvel_notice_log) so re-running the job never duplicates notifications or activity.
 */
@Service
public class StokvelNoticeService {

    private static final int MISSED_LOOKBACK = 3;
    private static final int PAYOUT_WARNING_DAYS = 3;

    private final StokvelSupport support;
    private final StokvelProfileRepository profiles;
    private final StokvelNoticeLogRepository log;
    private final StokvelContributionService contributionService;
    private final StokvelRotationSlotRepository slots;
    private final StokvelCycleRepository cycles;
    private final NotificationService notificationService;
    private final ActivityService activityService;

    public StokvelNoticeService(StokvelSupport support, StokvelProfileRepository profiles,
                                StokvelNoticeLogRepository log, StokvelContributionService contributionService,
                                StokvelRotationSlotRepository slots, StokvelCycleRepository cycles,
                                NotificationService notificationService, ActivityService activityService) {
        this.support = support;
        this.profiles = profiles;
        this.log = log;
        this.contributionService = contributionService;
        this.slots = slots;
        this.cycles = cycles;
        this.notificationService = notificationService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<String> stokvelCircleIds() {
        return profiles.findAll().stream().map(p -> p.getCircle().getId()).toList();
    }

    @Transactional
    public void runFor(String circleId, LocalDate today) {
        StokvelProfile p = profiles.findByCircleId(circleId).orElse(null);
        if (p == null || p.getContributionAmount() == null) {
            return;
        }
        int cur = StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(), today);
        if (cur < 0) {
            return;
        }
        List<CircleMembership> members = support.members(circleId);
        Map<String, BigDecimal> confirmed = contributionService.confirmedMap(circleId);
        String circleName = p.getCircle().getName();

        for (CircleMembership m : members) {
            String uid = m.getUser().getId();
            MemberPeriodStatus now = contributionService.memberStatus(p, m, cur, confirmed, today);
            if ((now.status() == ContributionStatus.DUE || now.status() == ContributionStatus.PARTIAL)
                    && firstTime(p, m, cur, "DUE")) {
                notificationService.notify(m.getUser(), NotificationType.STOKVEL_CONTRIBUTION_DUE,
                        "Your contribution of " + p.getCircle().getCurrency() + " " + p.getContributionAmount()
                                + " is due in " + circleName, circleId);
            }
            int first = contributionService.firstOwedPeriod(p, m);
            for (int k = Math.max(first, cur - MISSED_LOOKBACK); k < cur; k++) {
                MemberPeriodStatus s = contributionService.memberStatus(p, m, k, confirmed, today);
                if (s.status() == ContributionStatus.MISSED && firstTime(p, m, k, "MISSED")) {
                    notificationService.notify(m.getUser(), NotificationType.STOKVEL_CONTRIBUTION_MISSED,
                            "You missed a contribution in " + circleName, circleId);
                    activityService.record(p.getCircle(), m.getUser(), ActivityType.CONTRIBUTION_MISSED,
                            m.getUser().getFullName() + " missed a contribution");
                }
            }
        }

        if (p.getStokvelType() == StokvelType.ROTATIONAL) {
            LocalDate end = StokvelSchedule.periodEndExclusive(p.getStartDate(), p.getContributionFrequency(), cur);
            if (ChronoUnit.DAYS.between(today, end) <= PAYOUT_WARNING_DAYS) {
                List<String> ids = slots.findByCircleIdOrderByPositionAsc(circleId).stream()
                        .map(s -> s.getUser().getId()).toList();
                Set<String> active = members.stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
                String ben = RotationOrder.beneficiary(ids, active, (int) cycles.countByCircleId(circleId) + 1);
                members.stream().filter(m -> m.getUser().getId().equals(ben)).findFirst()
                        .filter(m -> firstTime(p, m, cur, "UPCOMING"))
                        .ifPresent(m -> notificationService.notify(m.getUser(), NotificationType.STOKVEL_PAYOUT_UPCOMING,
                                "Your payout in " + circleName + " is coming up", circleId));
            }
        }
    }

    private boolean firstTime(StokvelProfile p, CircleMembership m, int period, String kind) {
        String circleId = p.getCircle().getId();
        if (log.existsByCircleIdAndUserIdAndPeriodIndexAndKind(circleId, m.getUser().getId(), period, kind)) {
            return false;
        }
        StokvelNoticeLog row = new StokvelNoticeLog();
        row.setCircle(p.getCircle());
        row.setUser(m.getUser());
        row.setPeriodIndex(period);
        row.setKind(kind);
        log.save(row);
        return true;
    }
}
