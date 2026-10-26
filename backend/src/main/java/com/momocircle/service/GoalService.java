package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.GoalDtos.CreateGoalRequest;
import com.momocircle.dto.GoalDtos.GoalResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.ContributionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class GoalService {

    private final CircleGoalRepository goalRepository;
    private final ContributionRepository contributionRepository;
    private final CircleService circleService;
    private final ActivityService activityService;
    private final FinancialValidationService financialValidationService;

    public GoalService(CircleGoalRepository goalRepository,
                       ContributionRepository contributionRepository,
                       CircleService circleService,
                       ActivityService activityService,
                       FinancialValidationService financialValidationService) {
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.circleService = circleService;
        this.activityService = activityService;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public GoalResponse create(String circleId, User actor, CreateGoalRequest req) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        financialValidationService.requireValidAmount(req.targetAmount(), "targetAmount");

        CircleGoal goal = new CircleGoal();
        goal.setCircle(membership.getCircle());
        goal.setCreatedBy(actor);
        goal.setTitle(req.title().trim());
        goal.setDescription(req.description());
        goal.setTargetAmount(req.targetAmount());
        goal.setDeadline(req.deadline());
        goal = goalRepository.save(goal);

        activityService.record(membership.getCircle(), actor, ActivityType.GOAL_CREATED,
                actor.getFullName() + " created goal \"" + goal.getTitle() + "\"");

        return toResponse(goal);
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        return goalRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GoalResponse close(String circleId, String goalId, User actor) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);

        CircleGoal goal = requireGoal(circleId, goalId);
        goal.setStatus(GoalStatus.CLOSED);
        goal = goalRepository.save(goal);

        activityService.record(membership.getCircle(), actor, ActivityType.GOAL_CLOSED,
                actor.getFullName() + " closed goal \"" + goal.getTitle() + "\"");

        return toResponse(goal);
    }

    /**
     * Re-evaluates a goal against its current raised amount and flips it to
     * REACHED (recording an activity event) the first time the target is met.
     * Called after a contribution is earmarked to the goal.
     */
    @Transactional
    public void refreshStatus(CircleGoal goal, User actor) {
        if (goal.getStatus() != GoalStatus.ACTIVE) {
            return;
        }
        BigDecimal raised = raisedFor(goal);
        if (raised.compareTo(goal.getTargetAmount()) >= 0) {
            goal.setStatus(GoalStatus.REACHED);
            goalRepository.save(goal);
            activityService.record(goal.getCircle(), actor, ActivityType.GOAL_REACHED,
                    "Goal \"" + goal.getTitle() + "\" has been reached");
        }
    }

    /** Admin-only. ACTIVE -> PAUSED. Stops new earmarked contributions; changes no financial record. */
    @Transactional
    public GoalResponse pause(String circleId, String goalId, User actor) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        CircleGoal goal = requireGoal(circleId, goalId);
        if (goal.getStatus() == GoalStatus.PAUSED) return toResponse(goal);
        if (goal.getStatus() != GoalStatus.ACTIVE) {
            throw ApiException.conflict("Only an active goal can be paused");
        }
        goal.setStatus(GoalStatus.PAUSED);
        goal = goalRepository.save(goal);
        activityService.record(membership.getCircle(), actor, ActivityType.GOAL_PAUSED,
                actor.getFullName() + " paused goal \"" + goal.getTitle() + "\"");
        return toResponse(goal);
    }

    /** Admin-only. PAUSED -> ACTIVE (or REACHED if the recorded total already meets the target). */
    @Transactional
    public GoalResponse resume(String circleId, String goalId, User actor) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        CircleGoal goal = requireGoal(circleId, goalId);
        if (goal.getStatus() == GoalStatus.ACTIVE) return toResponse(goal);
        if (goal.getStatus() != GoalStatus.PAUSED) {
            throw ApiException.conflict("Only a paused goal can be resumed");
        }
        goal.setStatus(GoalStatus.ACTIVE);
        goal = goalRepository.save(goal);
        activityService.record(membership.getCircle(), actor, ActivityType.GOAL_RESUMED,
                actor.getFullName() + " resumed goal \"" + goal.getTitle() + "\"");
        refreshStatus(goal, actor);
        return toResponse(goal);
    }

    /** Guard used before a contribution is earmarked to a goal. */
    public void requireAcceptingContributions(CircleGoal goal) {
        if (goal != null && goal.getStatus() == GoalStatus.PAUSED) {
            throw ApiException.conflict("This goal is paused and is not accepting contributions right now");
        }
    }

    public CircleGoal requireGoal(String circleId, String goalId) {
        CircleGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));
        if (!goal.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Goal does not belong to this circle");
        }
        return goal;
    }

    private BigDecimal raisedFor(CircleGoal goal) {
        BigDecimal raised = contributionRepository
                .sumConfirmedAmountForGoal(goal.getId(), PaymentStatus.SUCCESSFUL);
        return raised == null ? BigDecimal.ZERO : raised;
    }

    private GoalResponse toResponse(CircleGoal goal) {
        BigDecimal raised = raisedFor(goal);
        Integer progress = null;
        if (goal.getTargetAmount().compareTo(BigDecimal.ZERO) > 0) {
            progress = raised.multiply(BigDecimal.valueOf(100))
                    .divide(goal.getTargetAmount(), 0, RoundingMode.FLOOR)
                    .min(BigDecimal.valueOf(100))
                    .intValue();
        }
        return new GoalResponse(
                goal.getId(),
                goal.getCircle().getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getTargetAmount(),
                raised,
                progress,
                goal.getDeadline(),
                goal.getStatus(),
                goal.getCreatedBy().getFullName(),
                goal.getCreatedAt());
    }
}
