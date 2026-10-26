package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.ApprovalRuleType;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.CircleDtos.CircleResponse;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.GoalDtos.GoalResponse;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.StokvelContributionLinkRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.stokvel.StokvelPermissions.Action;
import com.momocircle.stokvel.StokvelSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stokvel creation, settings, overview and goal progress. Extends Circle; adds no wallet and no ledger. */
@Service
public class StokvelService {

    private final CircleService circleService;
    private final CircleRepository circleRepository;
    private final StokvelProfileRepository profiles;
    private final StokvelContributionLinkRepository links;
    private final ContributionRepository contributions;
    private final GoalService goalService;
    private final ActivityService activityService;
    private final FinancialValidationService validation;
    private final StokvelSupport support;
    private final StokvelRotationService rotationService;

    public StokvelService(CircleService circleService, CircleRepository circleRepository,
                          StokvelProfileRepository profiles, StokvelContributionLinkRepository links,
                          ContributionRepository contributions, GoalService goalService,
                          ActivityService activityService, FinancialValidationService validation,
                          StokvelSupport support, StokvelRotationService rotationService) {
        this.circleService = circleService;
        this.circleRepository = circleRepository;
        this.profiles = profiles;
        this.links = links;
        this.contributions = contributions;
        this.goalService = goalService;
        this.activityService = activityService;
        this.validation = validation;
        this.support = support;
        this.rotationService = rotationService;
    }

    @Transactional
    public StokvelOverview create(User owner, CreateStokvelRequest req) {
        String currency = req.currency().trim().toUpperCase();
        validation.requireValidCurrency(currency);
        if (req.contributionAmount() != null) {
            validation.requireValidAmount(req.contributionAmount(), "contributionAmount");
        }
        if (req.targetAmount() != null) {
            validation.requireValidAmount(req.targetAmount(), "targetAmount");
        }
        if (req.stokvelType() == StokvelType.ROTATIONAL && req.contributionAmount() == null) {
            throw ApiException.badRequest("A rotational stokvel needs a fixed contribution amount");
        }
        if (req.stokvelType() == StokvelType.GOAL_BASED && req.targetAmount() == null) {
            throw ApiException.badRequest("A goal-based stokvel needs a target amount");
        }
        LocalDate start = req.startDate() != null ? req.startDate() : support.today();
        if (req.targetDate() != null && !req.targetDate().isAfter(start)) {
            throw ApiException.badRequest("Target date must be after the start date");
        }
        ApprovalRuleType rule = req.approvalRule() == null ? ApprovalRuleType.SINGLE : req.approvalRule();
        if (rule == ApprovalRuleType.THRESHOLD && req.approvalThreshold() == null) {
            throw ApiException.badRequest("A threshold approval rule needs approvalThreshold");
        }

        CircleResponse created = circleService.createForStokvel(owner, new CreateCircleRequest(
                req.name(), req.description(), CircleType.STOKVEL, currency, req.targetAmount()));
        Circle circle = circleRepository.findById(created.id()).orElseThrow();

        StokvelProfile p = new StokvelProfile();
        p.setCircle(circle);
        p.setStokvelType(req.stokvelType());
        p.setContributionAmount(req.contributionAmount());
        p.setContributionFrequency(req.frequency());
        p.setStartDate(start);
        p.setTargetDate(req.targetDate());
        p.setApprovalRule(rule);
        p.setApprovalThreshold(rule == ApprovalRuleType.THRESHOLD ? req.approvalThreshold() : null);
        p.setMembersCanVote(Boolean.TRUE.equals(req.membersCanVote()));
        if (req.targetAmount() != null) {
            // Reuse the existing goal machinery: contributions are earmarked to it and GOAL_REACHED fires from it.
            GoalResponse g = goalService.create(circle.getId(), owner, new CreateGoalRequest(
                    req.name().trim(), req.description(), req.targetAmount(), req.targetDate()));
            p.setGoal(goalService.requireGoal(circle.getId(), g.id()));
        }
        p = profiles.save(p);

        if (p.getStokvelType() == StokvelType.ROTATIONAL) {
            rotationService.syncSlots(p);
        }
        activityService.record(circle, owner, ActivityType.STOKVEL_CREATED,
                owner.getFullName() + " created the " + req.stokvelType().name().toLowerCase().replace('_', ' ')
                        + " stokvel");
        return overview(circle.getId(), owner);
    }

    @Transactional(readOnly = true)
    public List<StokvelSummary> listForUser(User user) {
        List<StokvelSummary> out = new ArrayList<>();
        for (StokvelProfile p : profiles.findForUser(user.getId())) {
            Circle c = p.getCircle();
            CircleMembership m = circleService.requireMembership(c.getId(), user);
            out.add(new StokvelSummary(c.getId(), c.getName(), p.getStokvelType(), c.getCurrency(),
                    p.getContributionAmount(), p.getContributionFrequency(), m.getRole(),
                    circleService.membershipsOf(c.getId()).size()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public StokvelOverview overview(String circleId, User viewer) {
        var ctx = support.require(circleId, viewer);
        StokvelProfile p = ctx.profile();
        Circle c = ctx.circle();
        LocalDate today = support.today();
        int period = StokvelSchedule.periodIndex(p.getStartDate(), p.getContributionFrequency(), today);
        LocalDate ps = period < 0 ? p.getStartDate()
                : StokvelSchedule.periodStart(p.getStartDate(), p.getContributionFrequency(), period);
        LocalDate pe = StokvelSchedule.periodEndExclusive(p.getStartDate(), p.getContributionFrequency(), Math.max(period, 0))
                .minusDays(1);
        BigDecimal total = support.confirmedContributions(circleId);
        BigDecimal committed = support.committed(circleId);
        return new StokvelOverview(circleId, c.getName(), c.getDescription(), c.getCurrency(), ctx.role(),
                settings(p), circleService.membershipsOf(circleId).size(), period, ps, pe,
                total, committed, total.subtract(committed).max(BigDecimal.ZERO), StokvelSupport.FUNDS_NOTICE);
    }

    @Transactional
    public SettingsView updateSettings(String circleId, User actor, UpdateSettingsRequest req) {
        var ctx = support.requireForUpdate(circleId, actor);
        support.require(ctx, Action.UPDATE_SETTINGS);
        StokvelProfile p = ctx.profile();

        boolean financialChange = req.contributionAmount() != null || req.frequency() != null || req.startDate() != null;
        if (financialChange) {
            // Changing the expected amount/cadence would silently re-grade past periods.
            if (links.existsByCircleId(circleId) || p.isRotationLocked()) {
                throw ApiException.conflict(
                        "Contribution amount, frequency and start date are fixed once contributions or payouts exist");
            }
            if (req.contributionAmount() != null) {
                validation.requireValidAmount(req.contributionAmount(), "contributionAmount");
                p.setContributionAmount(req.contributionAmount());
            }
            if (req.frequency() != null) p.setContributionFrequency(req.frequency());
            if (req.startDate() != null) p.setStartDate(req.startDate());
        }
        ApprovalRuleType rule = req.approvalRule() != null ? req.approvalRule() : p.getApprovalRule();
        Integer threshold = req.approvalThreshold() != null ? req.approvalThreshold() : p.getApprovalThreshold();
        if (rule == ApprovalRuleType.THRESHOLD && threshold == null) {
            throw ApiException.badRequest("A threshold approval rule needs approvalThreshold");
        }
        p.setApprovalRule(rule);
        p.setApprovalThreshold(rule == ApprovalRuleType.THRESHOLD ? threshold : null);
        if (req.membersCanVote() != null) p.setMembersCanVote(req.membersCanVote());
        if (req.requestValidityDays() != null) p.setRequestValidityDays(req.requestValidityDays());
        p = profiles.save(p);
        // Existing requests keep the rule snapshot they were created with.
        activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_SETTINGS_UPDATED,
                actor.getFullName() + " updated the stokvel settings");
        return settings(p);
    }

    @Transactional(readOnly = true)
    public GoalProgress goalProgress(String circleId, User viewer) {
        var ctx = support.require(circleId, viewer);
        StokvelProfile p = ctx.profile();
        CircleGoal goal = p.getGoal();
        if (goal == null) {
            throw ApiException.notFound("This stokvel has no shared target");
        }
        BigDecimal raised = contributions.sumConfirmedAmountForGoal(goal.getId(), PaymentStatus.SUCCESSFUL);
        raised = raised == null ? BigDecimal.ZERO : raised;
        BigDecimal target = goal.getTargetAmount();
        int percent = target.signum() > 0
                ? raised.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.FLOOR)
                        .min(BigDecimal.valueOf(100)).intValue()
                : 0;
        Map<String, BigDecimal> perMember = new LinkedHashMap<>();
        var mine = support.members(circleId);
        mine.forEach(m -> perMember.put(m.getUser().getId(), BigDecimal.ZERO));
        for (var c : contributions.findByCircleIdOrderByCreatedAtDesc(circleId)) {
            if (c.getGoal() != null && c.getGoal().getId().equals(goal.getId())
                    && c.getPayment().getStatus() == PaymentStatus.SUCCESSFUL) {
                perMember.merge(c.getContributor().getId(), c.getPayment().getAmount(), BigDecimal::add);
            }
        }
        List<MemberProgress> rows = mine.stream()
                .map(m -> new MemberProgress(m.getUser().getId(), m.getUser().getFullName(),
                        perMember.getOrDefault(m.getUser().getId(), BigDecimal.ZERO)))
                .toList();
        return new GoalProgress(goal.getId(), target, raised, target.subtract(raised).max(BigDecimal.ZERO),
                percent, p.getTargetDate() != null ? p.getTargetDate() : goal.getDeadline(), rows);
    }

    SettingsView settings(StokvelProfile p) {
        return new SettingsView(p.getStokvelType(), p.getContributionAmount(), p.getContributionFrequency(),
                p.getStartDate(), p.getTargetDate(), p.getGoal() == null ? null : p.getGoal().getId(),
                p.getApprovalRule(), p.getApprovalThreshold(), p.isMembersCanVote(), p.getRequestValidityDays(),
                p.isRotationLocked(), p.getRotationLockedAt());
    }
}
