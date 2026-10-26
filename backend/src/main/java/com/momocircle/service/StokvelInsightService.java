package com.momocircle.service;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.stokvel.StokvelSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY helper for the assistant and the UI: deterministic explanations, forecasts and scenarios.
 * It has no write dependencies on purpose, so nothing built on it can approve, spend, reorder or change members.
 */
@Service
public class StokvelInsightService {

    private final StokvelSupport support;
    private final StokvelService stokvelService;
    private final StokvelRotationService rotationService;

    public StokvelInsightService(StokvelSupport support, StokvelService stokvelService,
                                 StokvelRotationService rotationService) {
        this.support = support;
        this.stokvelService = stokvelService;
        this.rotationService = rotationService;
    }

    @Transactional
    public InsightsView insights(String circleId, User viewer, BigDecimal scenarioAmount) {
        var ctx = support.require(circleId, viewer);
        var p = ctx.profile();
        StokvelOverview o = stokvelService.overview(circleId, viewer);
        String cur = o.currency();
        int members = (int) o.memberCount();
        String freq = p.getContributionFrequency().name().toLowerCase();

        String summary;
        if (p.getContributionAmount() != null) {
            BigDecimal perPeriod = p.getContributionAmount().multiply(BigDecimal.valueOf(members));
            summary = "Each of " + members + " members is expected to contribute " + cur + " "
                    + p.getContributionAmount() + " " + freq + ", so " + cur + " " + perPeriod
                    + " is expected per period. Tracked confirmed contributions so far: " + cur + " "
                    + o.trackedContributions() + ". " + StokvelSupport.FUNDS_NOTICE;
        } else {
            summary = "Tracked confirmed contributions so far: " + cur + " " + o.trackedContributions() + ". "
                    + StokvelSupport.FUNDS_NOTICE;
        }

        String rotation = null;
        if (p.getStokvelType() == StokvelType.ROTATIONAL) {
            RotationView r = rotationService.get(circleId, viewer);
            rotation = (r.locked() ? "The payout order is locked. " : "The order follows join order unless the founder changes it before the first payout. ")
                    + r.completedCycles() + " payout(s) recorded. "
                    + (r.currentBeneficiaryName() != null ? "Next beneficiary: " + r.currentBeneficiaryName() + ". " : "")
                    + (r.upcomingBeneficiaryName() != null ? "After that: " + r.upcomingBeneficiaryName() + "." : "");
        }

        String forecast = null;
        List<String> scenarios = new ArrayList<>();
        if (p.getGoal() != null) {
            GoalProgress g = stokvelService.goalProgress(circleId, viewer);
            if (g.remaining().signum() == 0) {
                forecast = "The shared target has been reached.";
            } else if (p.getContributionAmount() != null && members > 0) {
                BigDecimal perPeriod = p.getContributionAmount().multiply(BigDecimal.valueOf(members));
                int periods = g.remaining().divide(perPeriod, 0, RoundingMode.CEILING).intValue();
                int current = Math.max(StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(),
                        support.today()), 0);
                forecast = cur + " " + g.remaining() + " remains. At the expected amount that is about " + periods
                        + " more " + freq + " period(s), reaching the target around "
                        + StokvelSchedule.periodStart(p.getStartDate(), p.getContributionFrequency(), current + periods)
                        + " if everyone pays in full.";
            }
            if (scenarioAmount != null && scenarioAmount.signum() > 0 && members > 0 && g.remaining().signum() > 0) {
                BigDecimal perPeriod = scenarioAmount.multiply(BigDecimal.valueOf(members));
                int periods = g.remaining().divide(perPeriod, 0, RoundingMode.CEILING).intValue();
                scenarios.add("If each member paid " + cur + " " + scenarioAmount + " per " + freq + " period, the target would take about "
                        + periods + " period(s).");
            }
        }
        return new InsightsView(summary, rotation, forecast, scenarios,
                "This is an explanation only. Approvals, payments and purchases are made by people in the stokvel.");
    }
}
