package com.momocircle.service;

import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.enums.GoalStatus;
import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GoalPauseTest {
    private final GoalService service = new GoalService(mock(com.momocircle.repository.CircleGoalRepository.class),
            mock(com.momocircle.repository.ContributionRepository.class), mock(CircleService.class),
            mock(ActivityService.class), mock(FinancialValidationService.class));

    private static CircleGoal goal(GoalStatus s) { CircleGoal g = new CircleGoal(); g.setStatus(s); return g; }

    @Test void pausedGoalRejectsContributionsWithConflict() {
        ApiException e = assertThrows(ApiException.class, () -> service.requireAcceptingContributions(goal(GoalStatus.PAUSED)));
        assertEquals(409, e.getStatus().value());
    }
    @Test void otherStatusesAreUnchanged() {
        for (GoalStatus s : new GoalStatus[]{GoalStatus.ACTIVE, GoalStatus.REACHED, GoalStatus.CLOSED}) {
            assertDoesNotThrow(() -> service.requireAcceptingContributions(goal(s)), s.name());
        }
        assertDoesNotThrow(() -> service.requireAcceptingContributions(null));
    }
}
